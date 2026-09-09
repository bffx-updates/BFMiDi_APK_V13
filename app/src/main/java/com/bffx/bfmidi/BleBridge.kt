package com.bffx.bfmidi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

/**
 * Ponte Bluetooth LE do editor (window.BFMIDIBle) — set/2026.
 *
 * Fala com o BLE_CONTROL.h da controladora S3 pelo servico Nordic UART: o app
 * escreve linhas na RX e recebe as respostas por notify na TX. O que trafega
 * e o MESMO protocolo de linhas do USB CDC; quem monta linha, fila FIFO e
 * timeouts e o webApp/ble_transport.js — aqui so bytes, base64 pra
 * atravessar o evaluateJavascript sem escape.
 *
 * Contrato (espelhado em ble_transport.js):
 *   JS -> nativo: connect() / write(base64) / disconnect()
 *   nativo -> JS: window.BFMIDI_BLE_EVENT(type, payload)
 *     'state'  scanning|connecting|connected|disconnected|unsupported
 *     'error'  mensagem
 *     'rx'     base64 dos bytes recebidos
 *     'mtu'    MTU negociado (o JS fatia as escritas em mtu-3)
 *     'device' nome do pedal
 *
 * Decisoes: (a) o scan filtra pelo UUID do servico E pelo prefixo "BFMiDi-"
 * e fica com o MAIS FORTE visto em ~2,5 s — dois pedais no palco e o mais
 * perto ganha; (b) escritas sao SERIALIZADAS (uma em voo, proxima so no
 * onCharacteristicWrite) mesmo sendo write-no-response, porque o stack do
 * Android descarta a segunda escrita enquanto a primeira nao confirma;
 * (c) MTU 512 pedido logo apos conectar — sem isso cada notify traz 20 B e
 * um /config/global de 4 KB vira 200 pacotes; (d) permissoes: 12+ pede
 * BLUETOOTH_SCAN/CONNECT (neverForLocation), 10..11 pede localizacao fina
 * (exigida pelo scan) — quem pede e a Activity, por um launcher; a ponte so
 * diz o que falta (permissoesFaltando) e retoma em connect() quando concedido.
 */
class BleBridge(private val context: Context, private val webView: WebView,
                private val requestPermissions: (Array<String>) -> Unit) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NAME_PREFIX = "BFMiDi-"
        private const val SCAN_WINDOW_MS = 2500L
        private const val SCAN_TIMEOUT_MS = 12000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var best: ScanResult? = null
    private var pendingConnect = false   // connect() esperando permissao
    private var closed = false
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    // ── permissoes ────────────────────────────────────────────────────────
    fun permissoesFaltando(): Array<String> {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return need.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /** Chamado pela Activity quando o launcher de permissao devolve. */
    fun onPermissionResult(granted: Boolean) {
        if (!pendingConnect) return
        pendingConnect = false
        if (granted) startScan()
        else emit("error", "Permissão de Bluetooth negada. Libere nos ajustes do aparelho.")
    }

    // ── JS -> nativo ──────────────────────────────────────────────────────
    @JavascriptInterface fun connect() { handler.post { connectInternal() } }
    @JavascriptInterface fun disconnect() { handler.post { teardown(notify = true) } }
    @JavascriptInterface fun write(b64: String) {
        val bytes = try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Exception) { return }
        handler.post {
            writeQueue.add(bytes)
            pumpWrites()
        }
    }

    private fun connectInternal() {
        if (closed) return
        val ad = adapter
        if (ad == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            emit("state", "unsupported"); return
        }
        if (!ad.isEnabled) { emit("error", "Ligue o Bluetooth do aparelho e tente de novo."); return }
        val missing = permissoesFaltando()
        if (missing.isNotEmpty()) {
            pendingConnect = true
            requestPermissions(missing)
            return
        }
        teardown(notify = false)
        startScan()
    }

    // ── scan ──────────────────────────────────────────────────────────────
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try { result.device.name ?: result.scanRecord?.deviceName } catch (_: SecurityException) { null }
            if (name == null || !name.startsWith(NAME_PREFIX)) return
            val cur = best
            if (cur == null || result.rssi > cur.rssi) best = result
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emit("error", "Falha ao procurar por Bluetooth (código $errorCode).")
        }
    }
    private val scanPick = Runnable { pickAndConnect(false) }
    private val scanGiveUp = Runnable { pickAndConnect(true) }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { emit("error", "Bluetooth indisponível."); return }
        best = null
        scanning = true
        emit("state", "scanning")
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCb)
        } catch (e: SecurityException) {
            scanning = false
            emit("error", "Sem permissão de Bluetooth."); return
        }
        handler.postDelayed(scanPick, SCAN_WINDOW_MS)
        handler.postDelayed(scanGiveUp, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanPick)
        handler.removeCallbacks(scanGiveUp)
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    /** Janela curta fechou: conecta no mais forte; sem nenhum, segue ate o timeout. */
    private fun pickAndConnect(giveUp: Boolean) {
        val chosen = best
        if (chosen == null) {
            if (giveUp) {
                stopScan()
                emit("state", "disconnected")
            }
            return
        }
        stopScan()
        val name = try { chosen.device.name } catch (_: SecurityException) { null } ?: NAME_PREFIX
        emit("device", name)
        emit("state", "connecting")
        try {
            gatt = chosen.device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            emit("error", "Sem permissão para conectar por Bluetooth.")
        }
    }

    // ── GATT ──────────────────────────────────────────────────────────────
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.requestMtu(512) } catch (_: SecurityException) { discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    val wasUp = txChar != null
                    closeGatt()
                    if (wasUp) emit("state", "disconnected")
                    else emit("error", "Não foi possível conectar ao pedal (status $status).")
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            emit("mtu", if (status == BluetoothGatt.GATT_SUCCESS) mtu.toString() else "23")
            discover(g)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            val rx = svc?.getCharacteristic(RX_UUID)
            val tx = svc?.getCharacteristic(TX_UUID)
            if (svc == null || rx == null || tx == null) {
                handler.post { emit("error", "Este aparelho não é uma BFMiDi em modo Bluetooth."); teardown(false) }
                return
            }
            rxChar = rx
            try {
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                } else {
                    txChar = tx
                    handler.post { emit("state", "connected") }
                }
            } catch (_: SecurityException) {
                handler.post { emit("error", "Sem permissão de Bluetooth."); teardown(false) }
            }
            txChar = tx
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID) handler.post { emit("state", "connected") }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { writeInFlight = false; pumpWrites() }
        }
        // API 33+: entrega o valor no parametro; abaixo, no proprio objeto.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX_UUID) emit("rx", Base64.encodeToString(value, Base64.NO_WRAP))
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return  // a de 3 args ja tratou
            if (c.uuid == TX_UUID) emit("rx", Base64.encodeToString(c.value ?: return, Base64.NO_WRAP))
        }
    }

    private fun discover(g: BluetoothGatt) {
        try { g.discoverServices() } catch (_: SecurityException) {
            handler.post { emit("error", "Sem permissão de Bluetooth."); teardown(false) }
        }
    }

    private fun pumpWrites() {
        if (writeInFlight) return
        val g = gatt ?: run { writeQueue.clear(); return }
        val rx = rxChar ?: run { writeQueue.clear(); return }
        val next = writeQueue.poll() ?: return
        writeInFlight = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(rx, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                rx.value = next
                @Suppress("DEPRECATION")
                g.writeCharacteristic(rx)
            }
        } catch (_: SecurityException) { false }
        if (!ok) {
            // Stack ocupado: devolve pra frente da fila e tenta em 20 ms.
            writeInFlight = false
            writeQueue.addFirst(next)
            handler.postDelayed({ pumpWrites() }, 20)
        }
    }

    // ── encerramento ──────────────────────────────────────────────────────
    private fun closeGatt() {
        writeQueue.clear()
        writeInFlight = false
        rxChar = null
        txChar = null
        val g = gatt
        gatt = null
        if (g != null) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
    }

    private fun teardown(notify: Boolean) {
        stopScan()
        val wasUp = txChar != null
        closeGatt()
        if (notify && wasUp) emit("state", "disconnected")
    }

    fun close() {
        closed = true
        teardown(notify = false)
    }

    // ── nativo -> JS ──────────────────────────────────────────────────────
    private fun emit(type: String, payload: String) {
        handler.post {
            if (closed) return@post
            val p = payload.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript(
                "window.BFMIDI_BLE_EVENT && window.BFMIDI_BLE_EVENT('$type','$p')", null)
        }
    }
}
