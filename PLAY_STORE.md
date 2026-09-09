# Publicar o BFMiDi Editor na Google Play — passo a passo

Guia de publicação do app Android na Play Store, do build ao botão PUBLICAR.
Companion do [README.md](README.md) (instalação por sideload) e do
[ARQUITETURA.md](ARQUITETURA.md) (como o app é construído). Escrito em
set/2026, com as regras da Play em vigor nessa data — quando o Console pedir
algo que não está aqui, é regra nova: siga o Console.

> **O que muda entre o APK de sideload e a versão da loja** (ver `app/build.gradle`):
> a Play recebe um **bundle `.aab`** (não um APK) do flavor **`play`**, que **não
> tem o atualizador interno** — app da loja se atualizar por fora dela viola a
> política *Device and Network Abuse*, e a permissão `REQUEST_INSTALL_PACKAGES`
> reprovaria a revisão. O flavor **`github`** continua sendo o APK das Releases,
> com atualizador. O mesmo workflow gera os dois: `BFMIDI-editor.apk` e
> `BFMIDI-editor-play.aab`, anexados à Release do GitHub.

---

## 0. Antes de abrir o Console

1. **Preencha o e-mail de contato** em [play/privacy.html](play/privacy.html)
   (já preenchido: carlosedudalsin@gmail.com). É o mesmo e-mail que vai na
   ficha da loja.
2. Rode **`git pwa apk.bat`** na raiz do repo. Ele:
   - publica o PWA com a política de privacidade em
     **`https://bffx-updates.github.io/Editor_BFMiDi_v13/privacy.html`** — abra a
     URL e confira que carrega (o Console valida a URL);
   - copia a UI + `offline_seed.json` + `webapp_version.txt` pros assets, faz
     commit/push do `android_app/` e o **GitHub Actions** gera a Release com o
     `BFMIDI-editor-play.aab`.
3. Baixe o **`BFMIDI-editor-play.aab`** da Release mais nova em
   `github.com/bffx-updates/BFMiDi_APK_V13/releases`. Anote o número do build
   (`build-10xx`): é o `versionCode`, e a Play exige que ele **só cresça**.
4. Tenha em mãos os gráficos de [play/](play/):
   - `icon_512.png` — ícone da loja (512×512, obrigatório);
   - `feature_graphic_1024x500.png` — gráfico de destaque (1024×500, obrigatório);
   - **capturas de tela do celular**: de 2 a 8, retrato, tiradas no aparelho com o app
     instalado (o APK das Releases serve — a tela é idêntica). Sugestão: HOME com um
     preset, editor LIVE de um footswitch, GLOBAL > TELA, SYSTEM > MANUTENÇÃO. Mínimo
     320 px e máximo 3840 px no lado maior, proporção entre 16:9 e 9:16.
   - capturas de **tablet de 7" e 10"** são opcionais, mas sem elas a ficha não
     aparece completa para tablets.

---

## 1. Criar o app no Play Console

`play.google.com/console` → **Criar app**:

| Campo | Valor |
|---|---|
| Nome do app | `BFMiDi Editor` |
| Idioma padrão | Português (Brasil) — `pt-BR` |
| App ou jogo | App |
| Gratuito ou pago | **Gratuito** (irreversível: app gratuito nunca vira pago) |
| Declarações | marcar as duas (Políticas do programa para desenvolvedores; leis de exportação dos EUA) |

---

## 2. Painel "Configurar o app" (todas as tarefas)

Cada linha é uma tarefa do painel; a resposta certa para ESTE app:

| Tarefa | Resposta |
|---|---|
| **Política de privacidade** | `https://bffx-updates.github.io/Editor_BFMiDi_v13/privacy.html` |
| **Acesso ao app** | "Todas as funções estão disponíveis sem restrições" **e** cole nas instruções: *"O app é o editor de um pedal MIDI físico. Sem o pedal por perto ele abre em MODO OFFLINE, com uma cópia local de presets já carregada — todas as telas ficam acessíveis e editáveis. Nenhum login."* (isso evita reprovação por "não conseguimos testar") |
| **Anúncios** | Não, o app não tem anúncios |
| **Classificação de conteúdo** | Preencher o questionário IARC: categoria **Utilitário / Produtividade / Comunicação / Outros**; responder NÃO a tudo (violência, sexo, drogas, apostas, compra de itens, interação entre usuários, compartilhamento de localização). Resultado esperado: **Livre / Everyone** |
| **Público-alvo** | Faixa **18 anos ou mais** (ou 13+). O app **não** é direcionado a crianças → "Não" para "apelo a crianças" |
| **App de notícias** | Não |
| **App de rastreamento de contatos / status de COVID-19** | Não |
| **Segurança dos dados** | Ver a seção 3 abaixo |
| **Apps governamentais** | Não |
| **Recursos financeiros** | Nenhum |
| **Apps de saúde** | Nenhum recurso de saúde |
| **Categoria e detalhes de contato** | Categoria **Música e áudio** (alternativa: Ferramentas); e-mail de contato obrigatório; site opcional: `https://bffx-updates.github.io/Editor_BFMiDi_v13/` |
| **Ficha da loja** | Ver a seção 4 |

---

## 3. Formulário "Segurança dos dados"

O app **não coleta nem compartilha dados**. Respostas:

- *Seu app coleta ou compartilha algum dos tipos de dados exigidos?* → **Não**.
- Com "Não", o formulário termina aí e a ficha mostra "Nenhum dado coletado".

Se o Console insistir por causa das permissões declaradas (localização no
Android 10–12, dispositivos próximos no 13+): elas existem só para o botão
CONECTAR entrar no Wi-Fi do pedal; **a localização não é lida, guardada nem
transmitida** — é o próprio Android que condiciona o acesso a redes Wi-Fi a
essa permissão. Não há coleta, então a resposta continua **Não**.

Não há SDK de terceiros, analytics, anúncios nem ID de publicidade.

---

## 4. Ficha da loja (Store listing)

### Português (Brasil) — idioma padrão

**Nome (≤30):** `BFMiDi Editor`

**Descrição curta (≤80):**
```
Editor de presets da controladora MIDI BFMiDi, por Wi-Fi ou offline.
```

**Descrição completa (≤4000):**
```
O BFMiDi Editor é o editor oficial da controladora MIDI BFMiDi. Configure o seu pedal pelo celular, sem computador: presets, footswitches, cores dos LEDs, imagens de fundo, ícones e todas as opções do sistema.

COMO FUNCIONA
• Conecte o celular ao Wi-Fi do pedal (ou à mesma rede local) e o app encontra a BFMiDi sozinho.
• Cada mudança é enviada na hora para o pedal — o que você vê na tela é o que está no palco.
• Sem o pedal por perto? O app abre em MODO OFFLINE: você monta e ajusta os presets numa cópia local e depois leva tudo para o pedal com BACKUP e RESTAURAR.

O QUE DÁ PARA EDITAR
• Presets por banco (A–J), nome, Program Change, canal e envios extras.
• Modo LIVE de cada footswitch: STOMP, MACROS, MOMENTÂNEO, TAP TEMPO, SPIN, RAMP, SINGLE, STEPS, CONTROL.
• Aparência da tela do pedal: layouts, cores, fontes, ícones e imagens de fundo.
• Cores e brilho do anel de LEDs, chamada de presets por gesto, combos de dois footswitches.
• Modo Amigável com os nomes de CC/PC de dezenas de pedais e amplificadores (Kemper, Line 6, BOSS, Valeton, TONEX e outros) e aparelhos personalizados.
• Assistente de configuração passo a passo para o primeiro preset.
• Backup e restauração completos, com imagens e ícones.

REQUISITOS
• Uma controladora BFMiDi com firmware 13 ou mais recente.
• O app fala só com o pedal, pela rede local. Não coleta dados nem precisa de cadastro.
```

**Categoria:** Música e áudio · **Tags (opcional):** MIDI, guitarra, pedaleira.

### English (United States) — adicionar como segundo idioma

**Name:** `BFMiDi Editor`

**Short description:**
```
Preset editor for the BFMiDi MIDI foot controller, over Wi-Fi or offline.
```

**Full description:**
```
BFMiDi Editor is the official editor for the BFMiDi MIDI foot controller. Set up your pedal from your phone, no computer needed: presets, footswitches, LED colors, background images, icons and every system option.

HOW IT WORKS
• Join the pedal's Wi-Fi (or the same local network) and the app finds the BFMiDi by itself.
• Every change is sent to the pedal instantly — what you see on screen is what's on stage.
• No pedal around? The app opens in OFFLINE MODE: build and tweak presets on a local copy, then move everything to the pedal with BACKUP and RESTORE.

WHAT YOU CAN EDIT
• Presets per bank (A–J): name, Program Change, channel and extra messages.
• LIVE mode of each footswitch: STOMP, MACROS, MOMENTARY, TAP TEMPO, SPIN, RAMP, SINGLE, STEPS, CONTROL.
• The pedal's screen: layouts, colors, fonts, icons and background images.
• LED ring colors and brightness, preset calls by gesture, two-footswitch combos.
• Friendly Mode with CC/PC names for dozens of pedals and amps (Kemper, Line 6, BOSS, Valeton, TONEX and more) plus custom devices.
• Step-by-step setup wizard for your first preset.
• Full backup and restore, including images and icons.

REQUIREMENTS
• A BFMiDi controller running firmware 13 or newer.
• The app only talks to the pedal over the local network. No data collection, no account.
```

### Gráficos

| Item | Arquivo | Regra |
|---|---|---|
| Ícone do app | `play/icon_512.png` | 512×512 PNG |
| Gráfico de destaque | `play/feature_graphic_1024x500.png` | 1024×500 PNG/JPEG |
| Capturas de tela (celular) | você tira no aparelho | 2 a 8, retrato |
| Capturas de tablet 7" / 10" | opcional | idem |
| Vídeo do YouTube | opcional | — |

---

## 5. Assinatura pela Play (Play App Signing) — decisão de uma vez só

Na **primeira** vez que você subir um bundle, o Console pergunta que chave vai
assinar os APKs entregues aos usuários. **Isso não pode ser trocado depois**, e a
escolha define se quem hoje usa o APK de sideload consegue **trocar para a versão
da loja sem desinstalar**:

- **Recomendado — usar a chave que já existe** (`app/bfmidi-release.jks`, alias
  `bfmidi`, senha `bfmidi123`): a assinatura da loja fica idêntica à do sideload, e
  o Android aceita instalar um por cima do outro. No Console: *Integridade do app →
  Assinatura de apps → **Usar uma chave diferente** → **Exportar e fazer upload de
  uma chave de um keystore Java***. O Console entrega a ferramenta `pepk.jar` e uma
  chave pública `encryption_public_key.pem`; no PC (precisa de Java 17, o mesmo do
  CI):

  ```bash
  java -jar pepk.jar --keystore=app/bfmidi-release.jks --alias=bfmidi --output=bfmidi-signing.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
  ```

  Senha do keystore e da chave: `bfmidi123`. Suba o `bfmidi-signing.zip`. A mesma
  chave passa a valer como **chave de upload** (é a que o CI já usa no `.aab`).

- **Alternativa — deixar o Google gerar**: mais simples, mas a assinatura da loja
  será outra. Quem tem o APK de sideload precisará **desinstalar** para instalar a
  versão da Play, e o inverso também.

Guarde o `bfmidi-release.jks` fora do repo também (ele está commitado com senha
fixa; perder o arquivo perde a chave de upload — a Play deixa registrar uma nova,
mas é burocracia).

---

## 6. Primeira publicação: teste interno → teste fechado → produção

### 6.1 Teste interno (imediato, sem revisão)

**Testar e lançar → Teste interno → Criar nova versão**:
1. Faça upload do `BFMIDI-editor-play.aab`.
2. Nome da versão: sugestão `13.8 (build 10xx)` — o Console já sugere.
3. Notas da versão (pt-BR): "Primeira versão na Play Store."
4. **Testadores**: crie uma lista com os e-mails (Google) dos testadores → salve.
5. Avaliar e lançar. O link de participação (*opt-in*) aparece na aba
   **Testadores**; mande aos testadores, que instalam pela própria Play.

Instale no seu celular por esse link e confira: abre em MODO OFFLINE sem pedal,
acha o pedal quando ele liga, o teclado não cobre os campos e o header do editor
não fica atrás da barra de status (targetSdk 36 = edge-to-edge obrigatório).

### 6.2 Teste fechado — 12 testadores por 14 dias (conta pessoal)

Contas de desenvolvedor **pessoais** criadas depois de nov/2023 só ganham acesso à
produção depois de um **teste fechado com pelo menos 12 testadores que fiquem
inscritos por 14 dias seguidos** (a contagem reinicia se cair abaixo de 12).
Contas de **organização** (CNPJ) não passam por isso. Se a sua for pessoal:

1. **Testar e lançar → Teste fechado → Criar faixa** (ou use a faixa "Alpha").
2. Suba o mesmo `.aab` (ou o mais novo), adicione a lista de testadores (12+
   e-mails; amigos, alunos, clientes com o pedal), lance.
3. Peça a cada um que **entre pelo link de participação e instale** — só conta
   quem aceitou.
4. Depois dos 14 dias, o painel libera **Solicitar acesso à produção**: um
   questionário curto sobre o teste (o que foi testado, o que mudou). Responda
   com base no que os testadores reportaram; aprovação leva ~1–3 dias.

### 6.3 Produção

**Testar e lançar → Produção → Criar nova versão**: mesmo `.aab`, países
(**Todos os países/regiões** ou só Brasil), notas da versão → **Enviar para
revisão**. A revisão de um app novo pode levar de horas a **7 dias**. Enquanto
isso, todas as tarefas da seção 2 precisam estar concluídas (o botão de enviar
fica travado até lá).

---

## 7. Atualizações depois de publicado

1. Mexeu no editor ou no app nativo → `git pwa apk.bat` (gera a Release nova).
2. Baixe o `BFMIDI-editor-play.aab` novo (versionCode maior, automático).
3. **Produção → Criar nova versão** → upload → notas → enviar. Atualizações
   costumam ser revisadas em horas.
4. O usuário da loja recebe pela própria Play. O usuário de sideload continua
   recebendo pelo atualizador do APK das Releases — são dois canais, o mesmo código.

---

## 8. Checklist de reprovação (o que já derruba apps parecidos)

- [ ] `.aab` (não `.apk`), flavor **play** — o `.apk` das Releases é do flavor github.
- [ ] `targetSdk` 36 (Android 16): exigido para apps novos desde 31/ago/2026, e a
      exigência sobe uma API por ano (todo agosto). Está em `app/build.gradle`.
- [ ] Sem `REQUEST_INSTALL_PACKAGES` no bundle (só no manifesto do flavor github).
- [ ] Política de privacidade acessível na URL cadastrada.
- [ ] "Acesso ao app" explicando o MODO OFFLINE (senão o revisor não consegue testar sem o pedal).
- [ ] Segurança dos dados = "não coleta".
- [ ] Classificação de conteúdo preenchida.
- [ ] Ícone 512, gráfico 1024×500 e ≥2 capturas de celular.
- [ ] Nome/descrições sem "melhor", "nº 1", nomes de marcas em destaque no título
      (as marcas na descrição, como referência de compatibilidade, são aceitas).
- [ ] Conta pessoal: 12 testadores × 14 dias antes da produção.
