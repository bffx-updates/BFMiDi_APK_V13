package com.bffx.bfmidi

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import androidx.annotation.RequiresApi
import java.io.IOException
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper WebView fullscreen do editor BFMIDI servido pelo pedal.
 *
 * O pedal serve a UI por HTTP local (sem HTTPS), o que impede a instalacao
 * como PWA no Android. Este app carrega a mesma UI direto num WebView — sem
 * barra de navegador e sem a exigencia de contexto seguro do PWA.
 *
 * Ao abrir, tenta o ultimo IP, descobre _http._tcp por NSD e valida a identidade
 * do pedal antes de entregar o host ao editor local.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var progressView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private val assetOrigin = "http://appassets.androidplatform.net"
    private val assetEntry = "$assetOrigin/assets/index.html"
    private lateinit var assetLoader: WebViewAssetLoader
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probing = AtomicBoolean(false)
    private var editorLoaded = false
    private var currentApiHost: String? = null
    // Resultado de uma sondagem que terminou ANTES de a pagina carregar: e
    // entregue no onPageFinished (ver deliverApiHost). `has` separado porque
    // null e um resultado valido ("nenhum pedal").
    private var hasPendingApi = false
    private var pendingApiHost: String? = null
    private var activityResumed = false
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networkReprobe = Runnable { if (activityResumed) probeAndLoad(false) }
    // Sondagem OCIOSA: enquanto nao ha pedal (currentApiHost == null) o
    // ConnectivityManager fica mudo — ligar o pedal com o celular JA na mesma
    // rede nao gera evento nenhum. Sem isto o editor ficaria no MODO OFFLINE
    // ate uma troca de Wi-Fi ou um onResume. Com pedal achado, o runnable so
    // se reagenda (o health check do editor cuida da queda).
    private val idleReprobeMs = 20_000L
    private val idleReprobe = object : Runnable {
        override fun run() {
            if (!activityResumed) return
            if (currentApiHost == null) probeAndLoad(false)
            mainHandler.postDelayed(this, idleReprobeMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        webView = WebView(this)
        configureWebView()
        root.addView(webView, frame())

        progressView = buildProgress()
        root.addView(progressView, frame())

        errorView = buildError { probeAndLoad() }
        errorView.visibility = View.GONE
        root.addView(errorView, frame())

        setContentView(root)

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

        // Botao "voltar" navega no historico do WebView em vez de fechar o app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkObserver()
        probeAndLoad(true)
        checkForUpdate()
    }

    // ── Atualizador interno ────────────────────────────────────────────────
    // Ao abrir, consulta a release mais recente no GitHub e, se o build for
    // mais novo que o instalado, oferece baixar e instalar o APK. Substitui o
    // fluxo manual (baixar a release na mao) — "rodei o bat -> o app avisa e
    // atualiza". Silencioso quando offline (ex.: conectado no AP do pedal, sem
    // internet): nesse caso so checa quando o celular tiver acesso a internet.
    // Repo da linha v13 (migrado de BFMIDI_Android em ago/2026). Apontar pro
    // repo antigo faz o app v13 se atualizar pro APK v12.
    private val updateApiUrl =
        "https://api.github.com/repos/bffx-updates/BFMiDi_APK_V13/releases/latest"
    private var pendingApkUrl: String? = null

    /** versionCode instalado = numero do build do CI (BF_VERSION_CODE). */
    private fun currentBuildNumber(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = (URL(updateApiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "BFMiDi-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode != 200) { conn.disconnect(); return@Thread }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                // tag = "build-<n>"; o <n> E o versionCode do build (1000 + run).
                val tag = json.optString("tag_name")
                val latest = tag.substringAfterLast('-').toIntOrNull() ?: return@Thread
                val assets = json.optJSONArray("assets") ?: return@Thread
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url"); break
                    }
                }
                val url = apkUrl ?: return@Thread
                if (latest > currentBuildNumber()) {
                    runOnUiThread { promptUpdate(latest, url) }
                }
            } catch (e: Exception) {
                // Offline / sem internet (ex.: AP do pedal) -> ignora silenciosamente.
            }
        }.start()
    }

    private fun promptUpdate(buildNum: Int, apkUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Atualização disponível")
            .setMessage("Há uma versão nova do editor (build $buildNum). Atualizar agora?")
            .setPositiveButton("Atualizar") { _, _ -> ensureInstallPermissionThenDownload(apkUrl) }
            .setNegativeButton("Agora não", null)
            .setCancelable(true)
            .show()
    }

    /** Android 8+ exige permissao "instalar apps desconhecidos" por app. */
    private fun ensureInstallPermissionThenDownload(apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingApkUrl = apkUrl
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:$packageName"))
            try { startActivity(intent) }
            catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
                catch (e2: Exception) {
                    Toast.makeText(this, "Habilite 'instalar apps desconhecidos' nas configuracoes.", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        downloadAndInstall(apkUrl)
    }

    private fun downloadAndInstall(apkUrl: String) {
        Toast.makeText(this, "Baixando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "BFMiDi-Android")
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                val file = File(cacheDir, "update.apk")
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                runOnUiThread { launchInstaller(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Falha ao baixar a atualização.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao abrir o instalador.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        // Retoma a instalacao depois que o usuario habilita "apps desconhecidos".
        val url = pendingApkUrl
        if (url != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls())
        ) {
            pendingApkUrl = null
            downloadAndInstall(url)
        }
        scheduleNetworkReprobe()
        mainHandler.removeCallbacks(idleReprobe)
        mainHandler.postDelayed(idleReprobe, idleReprobeMs)
    }

    override fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(networkReprobe)
        mainHandler.removeCallbacks(idleReprobe)
        super.onPause()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            // HTTP virtual e intencional: a API embarcada tambem e HTTP. A
            // origem continua sendo appassets.androidplatform.net, nao file://.
            .setHttpAllowed(true)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage (tema, idioma, IP fixado)
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            // LOAD_NO_CACHE: nunca serve recurso (app.js/app.css) do cache do
            // WebView — sempre le os assets atuais do APK. Junto com o
            // clearCache(true) no probeAndLoad, garante que, apos instalar um
            // APK novo, a UI nao apareca velha por causa de app.js cacheado.
            cacheMode = WebSettings.LOAD_NO_CACHE
            // O editor ja e responsivo — nao forcamos viewport.
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

            // A ponte JavaScript so existe na origem virtual confiavel. Links
            // externos saem para o navegador do sistema e nunca assumem a UI.
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val trustedAsset = uri.scheme == "http" &&
                    uri.host == "appassets.androidplatform.net" &&
                    (uri.port == -1 || uri.port == 80) &&
                    (uri.path ?: "").startsWith("/assets/")
                if (trustedAsset) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) { true }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(assetEntry)) {
                    editorLoaded = true
                    // Sondagem que terminou durante o load: entrega agora. O
                    // editor tem um stub de modulo que guarda a chamada ate o
                    // App() montar a ponte, entao nao ha corrida com o app.js.
                    if (hasPendingApi) {
                        hasPendingApi = false
                        deliverApiHost(pendingApiHost)
                    }
                }
                showWeb()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // So tratamos falha do frame principal (recursos isolados nao
                // derrubam a UI inteira).
                if (request.isForMainFrame) showError()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Upload de imagem/icone do editor abre o seletor de arquivos.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params?.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // Ponte de download: o WebView nao baixa blob:/<a download> sozinho.
        // O editor monta o backup e chama window.BFMIDIDownloader.saveText(...),
        // que grava o arquivo na pasta Downloads (ver DownloadBridge).
        webView.addJavascriptInterface(DownloadBridge(), "BFMIDIDownloader")
    }

    /**
     * Carrega a UI LOCAL (assets do APK) e aponta a API pro pedal.
     *
     * A UI sobe NA HORA, sem ?api= — o editor abre em MODO OFFLINE (dispositivo
     * virtual do offline_device.js, com os dados de offline_seed.json / do que
     * o usuario ja editou) — e a sondagem corre em paralelo. Antes o app ficava
     * no spinner ate a sondagem terminar (a descoberta NSD sozinha sao 2,2 s
     * fixos; sem pedal, ate ~20 s somando os timeouts) e, sem pedal, caia na
     * tela de conexao do editor. Hoje o resultado da sondagem chega pela ponte
     * JS: BFMIDI_SET_API(host) troca pro pedal real, BFMIDI_NETWORK_LOST()
     * deixa/devolve ao modo offline. Nunca atropela o IP salvo quando estamos
     * offline.
     */
    private fun probeAndLoad(initial: Boolean = false) {
        if (initial && !editorLoaded) {
            showProgress()
            webView.clearCache(true)
            webView.loadUrl(assetEntry)
        }
        if (!probing.compareAndSet(false, true)) return
        Thread {
            try {
                val prefs = getSharedPreferences("bfmidi_network", MODE_PRIVATE)
                val saved = prefs.getString("last_api", null)
                val candidates = linkedSetOf<String>()
                if (!saved.isNullOrBlank()) candidates.add(saved)
                candidates.addAll(discoverNsdCandidates(2200))
                candidates.add("http://192.168.4.1")
                // Compatibilidade com firmware <=13.6, anterior ao hostname
                // unico e ao TXT de descoberta.
                candidates.add("http://bfmidi.local")
                val apiHost = candidates.firstOrNull { reachableBfmidi(it) }
                if (apiHost != null) prefs.edit().putString("last_api", apiHost).apply()
                runOnUiThread { deliverApiHost(apiHost) }
            } finally {
                probing.set(false)
            }
        }.start()
    }

    /** Exige o JSON de identidade do firmware; roteador/portal nao passa. */
    private fun reachableBfmidi(base: String): Boolean {
        try {
            val c = (URL("$base/ping").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2200
                readTimeout = 2200
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
            }
            val body: String? = try {
                val code = c.responseCode
                if (code == 200) {
                    c.inputStream.bufferedReader().use { it.readText() }
                } else null
            } finally {
                c.disconnect()
            }
            if (body != null) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json != null && json.optBoolean("ok") &&
                    json.optString("product") == "BFMIDI") return true
            }
        } catch (_: Exception) { /* cai no fallback abaixo */ }

        // Roda SEMPRE que o /ping nao provou identidade — inclusive quando ele
        // nem chegou a responder. Ja foi condicionado a "answered", e isso
        // fazia qualquer tropeco no /ping (timeout, conexao cortada) virar
        // "nao e um BFMiDi", sem nunca tentar a rota que o firmware antigo tem.
        // Compatibilidade <=13.6: /ping caia no SPA/404. Ainda exige a forma
        // estrutural da configuracao BFMIDI, nunca aceita so um HTTP 200.
        return try {
            val c = (URL("$base/config/global").openConnection()
                    as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
            }
            val body: String? = try {
                if (c.responseCode == 200) {
                    c.inputStream.bufferedReader().use { it.readText() }
                } else null
            } finally {
                c.disconnect()
            }
            if (body == null) {
                false
            } else {
                val json = runCatching { JSONObject(body) }.getOrNull()
                json != null && (json.has("board") || json.has("chip"))
            }
        } catch (_: Exception) { false }
    }

    private fun deliverApiHost(apiHost: String?) {
        if (!editorLoaded) {
            // A pagina ainda esta carregando (probeAndLoad ja mandou o loadUrl):
            // guarda e entrega no onPageFinished. Sem ?api= na URL de proposito
            // — recarregar a pagina aqui jogaria fora o editor que acabou de
            // subir em modo offline.
            hasPendingApi = true
            pendingApiHost = apiHost
            return
        }
        if (apiHost != null && apiHost != currentApiHost) {
            currentApiHost = apiHost
            webView.evaluateJavascript(
                "window.BFMIDI_SET_API && window.BFMIDI_SET_API(${JSONObject.quote(apiHost)})",
                null
            )
        } else if (apiHost == null) {
            // Forca a proxima volta do MESMO IP (comum no AP 192.168.4.1) a
            // notificar o editor de novo, sem recarregar a pagina.
            currentApiHost = null
            webView.evaluateJavascript(
                "window.BFMIDI_NETWORK_LOST && window.BFMIDI_NETWORK_LOST()", null
            )
        }
        showWeb()
    }

    /**
     * Descobre o servico HTTP anunciado pelo ESP32. O MulticastLock existe
     * apenas durante a descoberta; deixa de drenar bateria logo depois.
     */
    @Suppress("DEPRECATION")
    private fun discoverNsdCandidates(timeoutMs: Long): List<String> {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("bfmidi-nsd").apply {
            setReferenceCounted(false)
        }
        val found = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceName.contains("bfmidi", ignoreCase = true) ||
                    !resolving.compareAndSet(false, true)) return
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                            resolving.set(false)
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host
                            if (host is Inet4Address) {
                                val port = if (info.port > 0) info.port else 80
                                found.add("http://${host.hostAddress}:$port")
                                done.countDown()
                            } else {
                                resolving.set(false)
                            }
                        }
                    })
                } catch (_: Exception) { resolving.set(false) }
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        return try {
            lock.acquire()
            val wifiNetwork = connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                wifiNetwork != null) {
                // No AP sem internet, a rede default pode continuar sendo a
                // celular. A sobrecarga API 33+ fixa o browse mDNS na Wi-Fi.
                val callbackExecutor = Executor { command -> mainHandler.post(command) }
                nsd.discoverServices(
                    "_http._tcp.", NsdManager.PROTOCOL_DNS_SD, wifiNetwork,
                    callbackExecutor, listener
                )
            } else {
                nsd.discoverServices(
                    "_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener
                )
            }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.toList().distinct()
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            if (lock.isHeld) lock.release()
        }
    }

    private fun registerNetworkObserver() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkReprobe()
            override fun onLost(network: Network) = scheduleNetworkReprobe()
            override fun onCapabilitiesChanged(
                network: Network, capabilities: NetworkCapabilities
            ) = scheduleNetworkReprobe()
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun scheduleNetworkReprobe() {
        mainHandler.removeCallbacks(networkReprobe)
        mainHandler.postDelayed(networkReprobe, 800)
    }

    // ── Troca de telas (WebView / progresso / erro) ─────────────────────
    private fun showWeb() {
        webView.visibility = View.VISIBLE
        progressView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showProgress() {
        progressView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        progressView.visibility = View.GONE
    }

    // ── Construcao das telas auxiliares (sem XML de layout) ─────────────
    private fun frame() = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    private fun buildProgress(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val accent = Color.parseColor("#ff6a1f")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(48), 0, px(48), 0)
            // Fundo escuro com um leve brilho laranja no topo (gradiente radial).
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1a130d"), Color.parseColor("#0a0a0c"))
            ).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = px(520).toFloat()
                setGradientCenter(0.5f, 0.32f)
            }
        }

        // Logo do BFMIDI.
        box.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(px(132), px(132)))

        // Titulo da marca / status principal.
        box.addView(TextView(this).apply {
            text = getString(R.string.connecting)
            setTextColor(accent)
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            setPadding(0, px(30), 0, 0)
        })

        // Dica secundaria.
        box.addView(TextView(this).apply {
            text = getString(R.string.connecting_hint)
            setTextColor(Color.parseColor("#8a8a92"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, px(10), 0, px(30))
        })

        // Spinner laranja (movimento).
        box.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(px(34), px(34)))

        return box
    }

    private fun buildError(onRetry: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0a0a0c"))
            setPadding(64, 0, 64, 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.err_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.err_body)
            setTextColor(Color.parseColor("#9a9aa2"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 36)
        })
        box.addView(Button(this).apply {
            text = getString(R.string.err_retry)
            setOnClickListener { onRetry() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return box
    }

    // ── Ponte de download (salvar o backup gerado pela UI) ──────────────
    /**
     * O WebView ignora downloads disparados por blob:/<a download>, entao o
     * editor (app.jsx) chama window.BFMIDIDownloader.saveText(nome, conteudo)
     * pra que o app grave o arquivo. Usado pelo backup (JSON). Retorna true se
     * gravou — o webApp usa isso pra nao exibir "sucesso" quando a gravacao
     * falhou. Roda numa thread propria do WebView (Toast via runOnUiThread).
     */
    inner class DownloadBridge {
        // Buffer do caminho fatiado (begin -> append* -> end). O conteudo e
        // acumulado como String (UTF-16, igual ao JS) e so convertido pra UTF-8
        // no fim — entao fatiar no meio de um par surrogate e inofensivo, a
        // concatenacao reconstroi a string identica. As chamadas da ponte sao
        // serializadas pelo WebView, entao o estado mutavel aqui e seguro.
        private val buffer = StringBuilder()
        private var pendingName: String? = null

        /** Caminho simples: o texto inteiro numa unica chamada (backup pequeno). */
        @JavascriptInterface
        fun saveText(fileName: String, content: String): Boolean =
            persist(sanitizeFileName(fileName), content)

        /** Inicia um download fatiado (backup grande, ex.: com imagens base64). */
        @JavascriptInterface
        fun begin(fileName: String) {
            buffer.setLength(0)
            pendingName = sanitizeFileName(fileName)
        }

        /** Acrescenta um pedaco do conteudo ao buffer. */
        @JavascriptInterface
        fun append(chunk: String) {
            buffer.append(chunk)
        }

        /** Finaliza o download fatiado: grava o que foi acumulado. */
        @JavascriptInterface
        fun end(): Boolean {
            val name = pendingName
            val content = buffer.toString()
            buffer.setLength(0)
            buffer.trimToSize()
            pendingName = null
            return if (name != null) persist(name, content) else false
        }

        /** Grava o texto em Downloads (API 29+) ou compartilha (Android < 10). */
        private fun persist(name: String, content: String): Boolean {
            return try {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToDownloadsQ(name, bytes)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.backup_saved, "Downloads/$name"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    // Android < 10: grava no cache e abre o compartilhamento
                    // (sem exigir permissao de armazenamento).
                    val file = File(cacheDir, name)
                    file.writeBytes(bytes)
                    runOnUiThread { shareFile(file) }
                }
                true
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.backup_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
                false
            }
        }
    }

    /** Mantem so caracteres seguros num nome de arquivo. */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isEmpty()) "bfmidi-backup.json" else cleaned
    }

    /** Grava na pasta publica Downloads via MediaStore (Android 10+, sem permissao). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsQ(fileName: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert nulo")
        val out = resolver.openOutputStream(uri) ?: throw IOException("openOutputStream nulo")
        out.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** Compartilha um arquivo (fallback de salvar no Android < 10). */
    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.backup_share)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.backup_failed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(networkReprobe)
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) }
            catch (_: Exception) {}
        }
        webView.destroy()
        super.onDestroy()
    }
}
