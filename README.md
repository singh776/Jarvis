# J.A.R.V.I.S. — Punjabi + English phone assistant

Samsung Galaxy S26 layi apna Jarvis. Punjabi ja English vich bolo, Jarvis sunda hai, sochda hai (Google Gemini naal) te bol ke jawab dinda hai.

## Ki kar sakda hai
- Har sawal da jawab — Punjabi (ਗੁਰਮੁਖੀ) ja English vich, awaaz naal
- **Kise vi app vich kamm (Screen control):** Jarvis aap screen dekh ke tap, type te scroll karda hai.
  "Wifi band kar", "Instagram te pehli post like kar", "Spotify te Diljit da gaana la", "Uber te ghar di ride dekh", "Screen te ki likhya padh ke suna"
- Call: "Mom nu call kar"
- SMS te WhatsApp: "Harpreet nu WhatsApp kar ki main 10 minute vich aa reha" (bhejan ton pehla puchhda hai)
- Notifications padhna: "Mere messages suna"
- Music: play / pause / agla gaana / pichla
- Volume, brightness, vibrate mode
- Back, Home, Lock screen, Screenshot, Notifications panel
- Apps kholna, YouTube, Google search, Maps
- Alarm, timer, torch, battery, time
- Gall-baat yaad rakhda (gole nu lamba daba ke nawi gall shuru)

Jarvis kade vi paise nahi bhejda, kuch khareedda nahi, te password nahi bharda. Uh kamm tusi aap karna.

## APK kiwen milegi
Is repo vich code push hunde hi **GitHub Actions** aap APK bana dinda hai (3–5 minute).
1. Repo de **Releases** section vich jao (phone te: repo page → "Releases").
2. Sab ton upar wali release vich `Jarvis.apk` te tap karo → download.
3. Install karo (pehli vaar "Install unknown apps" di permission deni paindi hai).

## Nawan v2.9
- **Nawan design (AI-app wargi):** khaali screen te "What's next, <naam>?" te chamakda tara-gola; thalle halki rangeen roshni jo awaaz naal hildi; sunan vele input bar lehraundi line ban jaandi; upar-khabbe "new chat".
- **App English vich**, gall-baat di bhasha tusi bol ke badlo: "Punjabi ch gall kar" / "talk in English". Translate: "is nu Punjabi ch translate kar…".
- **Awaaz badlo:** Settings → Voice → Voice — banda/zanani wali awaaz chuno (Deep / Normal / Higher + saariyan awaazan sun ke chuno).
- **Popup theek:** ✕, thalle swipe, ja bahar tap naal band; kade atakda nahi (20 sec watchdog). Kamm di progress hun upar nahi, ise popup vich.
- **Tez:** "YouTube khol" warge hukam AI di udeek bina turant khulde.

## Nawan v2.8
- **Side key → chhota popup:** puri app nahi khuldi; thalle Gemini wargi card (rangeen kinara + lehraundi line) aundi, sun ke jawab dindi te kamm kardi.
- **"Hey Singh AI" hun default BAND:** mic sirf side key / app kholan te chalda. Je "Singh AI" bol ke chalauna hai taan Settings → Voice → Hey Singh AI ON karo (os vele mic background vich sunda rehnda — Android hara mic-nishan dikhaunda).
- Nawan **app icon** (S + rangeen ring) te gola hun **center vich**, vichkaar ghumda chamakda tara.

## Nawan v2.7
- **"Singh AI" bolo — app band hove taan vi:** screen on hove taan "Singh AI" kaho. Upar chhota popup aunda (Gemini wangu), puri app nahi khuldi. Gall sun ke jawab dinda te kamm karda (app kholna, WhatsApp, call…). Popup te tap = puri app. ✕ = band. (Settings → Voice → Hey Singh AI; Screen Control ON chahida.)
- **Har vaar nawin chat:** app dubara kholo (2 minute baad ja side key naal) taan screen saaf, par Memory vali gallan yaad rehndiyan.
- **Tez jawab:** Flash model da "thinking" band, te gall khatam hon ton jaldi mic band.

## Nawan v2.6
- **"+" button (Gemini wangu):** Camera · Photos · Screen · Files.
  - Photo khicho ja chuno, ja PDF/file pao, te puchho: "ki likhya?", "Punjabi vich translate karo", "eh ki hai?"
  - **Screen:** Singh AI piche chala janda, ik chamakda button aunda. Jehri screen dikhauni hai othe jao, button dabao — Singh AI us screen di photo naal wapas aunda. (Screen Control ON chahida.)
  - Photo upar chip vich rehndi hai, tusi us baare kinne vi sawal puchh sakde ho; ✕ naal hatao.

## Nawan v2.5
- Naam hun **Singh AI** (upar te app icon de thalle).
- **WhatsApp seedha:** "Harpreet nu WhatsApp te bhej ki main aa reha" → chat seedhi khuldi, message likhya jaanda te Send aap dabda (Screen Control ON hove). "Likh de" kaho taan sirf likhda, bhejda nahi. Hun koi "bhejna hai?" wala sawal nahi.
- **Tez:** screen control hun screen rukan di udeek karda, lambi wait nahi; app pehlan seedhi khuldi hai.
- **Yaad rakhda hai:** "yaad rakh mera naam Mandeep hai" → hamesha yaad. Gallan app band karan ton baad vi yaad. Settings → Memory vich dekho / mitao.
- Nawe kamm: gaana chalao (Spotify / YouTube Music), Maps te rasta.

## Nawan v2.4
- Upar **MOMI** likhya aunda hai.
- **Auto Listen:** app khuldi hi mic aap chal penda hai, te jawab den ton baad fer sunda hai (Settings → Voice).
- Gole di nawin animation: ghumda gradient ring, bolan/sunan te lehraan.
- **Settings → API Key → Test key:** Google naal key check karke asli error dikhaunda hai, te je model key naal nahi chalda taan aap theek model chun lainda hai.
- 401 te hun sahi message: "Google ਨੇ API key ਨਹੀਂ ਮੰਨੀ".

## Nawan v2.0
- **Server busy (503) fix:** Gemini busy hove taan Jarvis aap 1s, 2s, 4s ruk ke dubara koshish karda hai, fer halke **Backup Model** (Flash-Lite) te chala janda hai. Sahi message dikhda hai — "API key check karo" sirf asli key/internet di galti te.
- **Gemini wargi screen:** neela-jamni-gulabi chamakda gola, chat bubbles, pill input bar, glow wala mic button.
- **iPhone wargi Settings:** AI (key, model, backup model), Voice (bhasha, speed, keep listening, side key), Appearance (Dark/Light, accent color), Permissions, About.
- Model de naam `Gemini.kt` de upar CONFIG vich (ja Settings → AI vich) badlo.

## Pehli vaar setup
1. **API key:** aistudio.google.com/apikey te jao → "Create API key" → copy.
2. Jarvis kholo → ⚙ Settings → AI → **API Key** → paste karo → Save.
3. **Punjabi awaaz:** Settings → General management → Text-to-speech → Google engine → ⚙ → Install voice data → **ਪੰਜਾਬੀ (India)** download karo. (Je eh nahi hove, Jarvis Punjabi English akhran vich bolega.)
4. ⚙ Settings → **Permissions** vich har line te tap karke **On** karo:
   - Mic, contacts, call, SMS
   - **Screen control** (sare kamm layi zaroori): Accessibility → Installed apps → Jarvis screen control → ON
   - Notifications padhna
   - Brightness
5. Je Screen control ON na hove te "Restricted setting" likhya aave: Settings → Apps → Jarvis → upar ⋮ → **Allow restricted settings** → fer ON karo.

## Side key naal kholna (asli Jarvis wangu)
Settings → Apps → Default apps → **Digital assistant app** → Jarvis chuno.
Fer side key (ja home button) lamba dabao te Jarvis aap sunna shuru kar dega.

## Buttons
- **Gola (reactor):** tap = bolo, lamba dabao = nawi gall-baat
- **ਪੰਜਾਬੀ / EN:** mic di bhasha badlo
- **➤:** likh ke puchho
- **⚙:** API key, model, auto-sunna on/off
