# BFMIDI Android — Arquitetura & Processo

Documento de referência de **como o app Android é construído, assinado, publicado
e atualizado**. Companion do [README.md](README.md) (que é o "comece por aqui").

---

## 1. Por que este app existe

O editor do BFMIDI é uma **web app (PWA)** servida pelo próprio pedal por **HTTP
local** (`http://192.168.4.1` no AP, ou no STA o IP ou `bfmidi.local` — o
mesmo nome nas duas interfaces; fica ambíguo com dois pedais na mesma rede).

No **iOS** dá pra "Adicionar à Tela de Início" e abrir em tela cheia. No **Android**
**não**: o Chrome só instala PWA em **HTTPS** (ou `localhost`), e o pedal é HTTP.
O service worker nem registra em HTTP → o botão "Instalar app" nunca aparece.

Tentar resolver com **HTTPS self-signed no firmware não compensa**: certificado não
confiável bloqueia o service worker do mesmo jeito (continuaria sem instalar), além
de custar ~22–42 KB de RAM por conexão TLS no ESP32-S2 e um rewrite do servidor.

**Solução:** um app Android nativo mínimo que renderiza a UI num **WebView**. O
WebView ignora as regras de contexto seguro/PWA — só desenha a tela em fullscreen.

---

## 2. Arquitetura

### 2.1. UI embutida (não vem do pedal)

> Esta é a decisão central. Mudou em jun/2026 (antes a UI vinha do pedal).

A tela do editor vive **dentro do APK**, em `app/src/main/assets/`. O WebView
carrega de `http://appassets.androidplatform.net/assets/index.html` por
`WebViewAssetLoader`. O app fala com o pedal **apenas
pela API JSON** (HTTP): `/bank/current`, `/config/global`, `/sw/params`, etc.

```
┌─────────────────────────── CELULAR (APK) ───────────────────────────┐
│  WebView                                                            │
│    appassets.androidplatform.net/assets/… ← UI (assets do APK)     │
│    app.js / app.css / icons                                        │
│            │                                                       │
│            │  fetch HTTP (?api=http://<pedal>)                      │
└────────────┼──────────────────────────────────────────────────────┘
             │  Wi-Fi (LAN)
             ▼
┌──────────────────── PEDAL (ESP32-S2) ───────────────────────────────┐
│  WebServer :80  →  só a API JSON é usada pelo app                   │
│  (continua servindo a UI também, pra quem usa pelo navegador)       │
└─────────────────────────────────────────────────────────────────────┘
```

**Vantagens:**
- **Mudança de tela = atualizar o APK** (não precisa reflashar a LittleFS do pedal).
- Carrega mais rápido (arquivos locais) e sem cache velho de PWA.
- A UI aparece mesmo sem o pedal por perto (mostra a tela de conexão do editor).

**O que continua exigindo flash do firmware:** mudanças no **firmware em si**
(novo endpoint/campo de API, comportamento do pedal). A *tela* não.

### 2.2. Resolução do endereço do pedal (`?api=`)

`api.js` do webApp resolve a base da API assim (ordem de prioridade):

1. `?api=<url>` na query string;
2. `localStorage['bfmidi_deviceApi']` (último host confirmado);
3. no APK/preview local → fallback `http://192.168.4.1`.

O nativo ([MainActivity.kt](app/src/main/java/com/bffx/bfmidi/MainActivity.kt))
carrega a UI local **imediatamente e sem `?api=`** — o editor sobe em MODO
OFFLINE (dispositivo virtual de `webApp/offline_device.js`, semeado por
`assets/offline_seed.json`) — e, em paralelo, usa NSD para descobrir
`_http._tcp`, tenta o último host salvo e o AP. Só aceita `/ping` com
`{product:"BFMIDI",ok:true}`; em firmware até 13.6, o fallback exige os campos
estruturais de `/config/global`. O resultado vai pela ponte JS: `BFMIDI_SET_API`
(pedal achado — o editor troca de aparelho e recarrega tudo) ou
`BFMIDI_NETWORK_LOST` (fica/volta ao offline). Se a sondagem termina antes de a
página carregar, o resultado é guardado e entregue no `onPageFinished` (o
editor tem um stub que segura a chamada até o `App()` montar). O
`ConnectivityManager` repete a descoberta quando o Wi‑Fi muda; sem pedal, um
`Runnable` ocioso repete a cada 20 s em primeiro plano (ligar o pedal com o
celular já na rede não gera evento). A tela de conexão do editor não é mais
mostrada pelo app (set/2026); o item 1 da lista acima (`?api=`) sobrevive só
para o preview local e para `?api=offline`, que força o modo offline no browser.

O app hoje mira `targetSdk 34`, portanto ainda não declara a permissão futura
`ACCESS_LOCAL_NETWORK`. Ao migrar para `targetSdk 37` (Android 17), adicionar a
permissão de runtime e o fluxo de consentimento antes de NSD/HTTP local; o uso de
NSD já deixa a descoberta no caminho recomendado pela plataforma.

### 2.3. Origem do WebView e API local

`file://` foi removido. A origem virtual HTTP mantém a UI local e permite falar
com a API HTTP sem mixed content. Os acessos perigosos ficam desligados:

```kotlin
allowFileAccess = false
allowContentAccess = false
allowFileAccessFromFileURLs = false
allowUniversalAccessFromFileURLs = false
```

O firmware libera CORS apenas para origens locais/privadas conhecidas e exige o
header `X-BFMIDI-Token` em todo POST. O token só é entregue quando o pareamento
entra pela interface AP.

### 2.4. Uploads

`WebChromeClient.onShowFileChooser` abre o seletor de arquivos do Android (para os
uploads de imagem/ícone do editor). Sem Web Serial/USB — só Wi-Fi.

---

## 3. Estrutura do projeto

```
android_app/
├─ settings.gradle / build.gradle / gradle.properties   # projeto Gradle
├─ app/
│  ├─ build.gradle                                       # config do módulo + signing + versão
│  └─ src/main/
│     ├─ AndroidManifest.xml                             # permissões, cleartext, activity
│     ├─ java/com/bffx/bfmidi/MainActivity.kt            # AssetLoader + NSD + rede
│     ├─ res/values/{strings,themes}.xml
│     ├─ res/xml/network_security_config.xml             # cleartext liberado (LAN)
│     ├─ res/mipmap-xxxhdpi/ic_launcher.png              # ícone
│     └─ assets/                                         # ★ UI do editor (buildada)
│        ├─ index.html  app.js  app.css  sw.js  manifest.webmanifest
│        └─ icons/…
├─ app/bfmidi-release.jks                                # chave de assinatura FIXA
└─ .github/workflows/build-apk.yml                       # CI
```

---

## 4. Build na nuvem (GitHub Actions)

Não há toolchain Android na máquina de dev — **o build roda no GitHub Actions**.
Workflow: [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml).

Dispara em **push na `main`** ou manualmente (**Actions → Build APK → Run workflow**).
Etapas:

1. `actions/checkout`
2. `setup-java` (Temurin 17) — traz o `keytool`.
3. `android-actions/setup-android` — SDK + licenças.
4. **Garante o keystore fixo** (ver §5).
5. `setup-gradle` (Gradle 8.7).
6. `gradle assembleRelease` (versão vinda do nº do run — ver §6).
7. Renomeia pra `BFMIDI-editor.apk`.
8. Sobe como **artifact** e publica num **Release** (`build-<run>`).

A UI nos `assets/` é empacotada automaticamente pelo `assembleRelease` (já está
commitada no repo — ver §7).

---

## 5. Assinatura (atualização "1 toque")

Pro Android instalar uma atualização **por cima** do app, os APKs precisam ter a
**mesma assinatura**. Build de *debug* gera chave nova a cada run do CI (runner
descartável) → assinaturas diferentes → "app não instalado".

Por isso usamos uma **chave FIXA**:

- `app/bfmidi-release.jks` — gerada **uma vez** pelo próprio CI (`keytool`) e
  **commitada de volta** no repo (com `[skip ci]` pra não disparar build extra).
  Nas execuções seguintes o passo vê que já existe e não faz nada.
- `app/build.gradle` define a `signingConfig release` apontando pra ela.
- Senha (`bfmidi123`) vive no repo. Aceitável: **app pessoal de sideload**; o pior
  caso seria alguém assinar um APK falso com a mesma chave (irrelevante aqui).

> Migração: os builds antigos (build-1/2, debug) tinham chave variável; trocar pra
> a chave fixa exigiu **uma desinstalação final**. Daí em diante, atualização é
> só baixar o APK novo e tocar **"Atualizar"**.

---

## 6. Versionamento automático

`app/build.gradle` lê do ambiente (default `1` / `1.0` em build local):

```gradle
versionCode = BF_VERSION_CODE   (= 1000 + github.run_number)
versionName = BF_VERSION_NAME   (= "13.0.<run>")
```

O offset de 1000 existe porque `run_number` reiniciou em 1 quando o APK migrou pro
repo `BFMiDi_APK_V13` (ago/2026) — o Android recusa instalar por cima de um APK com
`versionCode` maior. **Nunca baixe o offset.** A conta é feita num passo de shell
(`$GITHUB_ENV`), não em `${{ }}`: expressão de workflow do GitHub não tem aritmética.

O Release usa a tag `build-<versionCode>` (o MESMO número, não o run cru — o
atualizador interno compara o número da tag com o `versionCode` instalado). Assim
cada build é único e crescente (o Android sempre trata como atualização, nunca
downgrade).

---

## 7. Como atualizar a UI embutida (snapshot)

A `assets/` é a UI **buildada**. O firmware (privado, `PROJECT_ZERO`, **só local**)
**não** entra no repo do APK — só o editor compilado (que já é público em qualquer
pedal). Fluxo quando a tela muda:

```bash
# 1) build do webApp SEM gzip (AssetsPathHandler serve os nomes crus)
cd <repo do firmware>/webApp
BF_NO_GZIP=1 npm run build          # gera ../data/ descompactado

# 2) copia pra dentro do APK
cd ..
rm -rf android_app/app/src/main/assets && mkdir android_app/app/src/main/assets
cp data/index.html data/app.js data/app.css data/manifest.webmanifest data/sw.js \
   android_app/app/src/main/assets/
cp -r data/icons android_app/app/src/main/assets/icons

# 3) commit + push → o CI gera o APK novo
cd android_app && git add -A && git commit -m "Atualiza UI embutida" && git push
```

> **Por que SEM gzip:** o build de produção normal guarda `app.js.gz`/`app.css.gz`
> e o firmware adiciona `Content-Encoding: gzip`. O `AssetsPathHandler` procura
> `app.js`/`app.css`, portanto o snapshot usa `BF_NO_GZIP=1`.

---

## 8. Como atualizar o app NATIVO

Mudanças no Kotlin/manifest/recursos (tela de boot, sonda de IP, permissões):
basta editar e **push na `main`** → o CI gera o APK novo. Nada de assets a copiar.

---

## 9. Fluxo de atualização no celular

1. Abrir a aba **[Releases](https://github.com/bffx-updates/BFMiDi_APK_V13/releases)**
   no celular.
2. Baixar o `BFMIDI-editor.apk` mais novo.
3. Abrir o arquivo → **"Atualizar"** (mesma chave fixa = instala por cima, sem
   desinstalar, sem perder nada — não há dado local relevante; tudo vive no pedal).

---

## 10. Decisões de design (resumo do "porquê")

| Decisão | Motivo |
|---|---|
| WebView (não PWA) | Android não instala PWA de HTTP; WebView ignora a regra. |
| HTTPS self-signed **descartado** | Cert não confiável bloqueia o SW do mesmo jeito + custo de RAM/rewrite no S2. |
| UI embutida no APK | Mudança de tela vira atualização do APK; sem reflash da LittleFS; sem cache velho. |
| Build no GitHub Actions | Sem toolchain Android local. |
| Chave de assinatura fixa | Atualização "1 toque" (mesma assinatura). |
| Snapshot da UI no repo do APK (não o repo do firmware) | `PROJECT_ZERO` fica **local/privado** (código que não pode vazar); só o editor compilado, já público, vai pro repo do APK. |
| WebViewAssetLoader em origem HTTP virtual | Remove `file://` universal sem criar mixed content com a API HTTP. |
| `cleartextTrafficPermitted` global | O IP do STA varia; descoberta valida `/ping` antes de aceitar o host. |

---

## 11. Troubleshooting

- **"App não instalado" ao atualizar** → assinatura diferente. Só acontece migrando
  dos builds antigos (debug). Desinstale uma vez e instale o novo; daí em diante ok.
- **UI carrega mas não conecta no pedal** → confira Wi-Fi (mesmo AP/rede do pedal).
  Se mesmo conectado falhar, confira a permissão de rede local e CORS/token (§2.3). A
  tela de conexão do editor também deixa fixar o IP manualmente.
- **Tela em branco / assets não carregam** → faltou commitar a `assets/`, ou a UI
  foi buildada **com** gzip (precisa ser `BF_NO_GZIP=1`).
- **Build do CI falhou** → ver **Actions → run → logs**. Causas comuns: AGP×Gradle
  incompatíveis, SDK não baixado, keystore não gerado.
