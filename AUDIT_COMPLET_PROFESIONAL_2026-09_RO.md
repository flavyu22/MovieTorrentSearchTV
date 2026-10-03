# Audit complet profesional — MovieTorrentSearchTV

> **ACTUALIZARE 2026-09-03 (post-audit): reparațiile au fost aplicate.** Vezi
> „Anexa B — Reparații aplicate” la finalul documentului și intrarea
> „Unreleased — 2026-09-03” din `CHANGELOG.md`. Verdictele de mai jos descriu
> starea anterioară intervenției.

# Audit complet profesional — MovieTorrentSearchTV 2.1.0

- **Dată raport:** 3 septembrie 2026
- **Sursa auditată:** `MovieTorrentSearchTV-2.0.0-professional-repaired-2026-08-07-source` (working copy local, fără `.git`)
- **Versiune declarată în cod:** `2.1.0` (`app/build.gradle.kts`, `appVersionName`), `versionCode = 10`
- **Stil limbă raport:** română (conform rapoartelor anterioare din repository)
- **Auditor:** asistent tehnic (clarvoyant) — revizuire statică a întregului cod sursă, plus verificări empirice locale

---

## 0. Rezumat executiv

MovieTorrentSearchTV este un catalog Android TV (Kotlin + Jetpack Compose) bine structurat,
cu o separare clară pe straturi (`api`, `data`, `di`, `model`, `navigation`, `playback`,
`repository`, `security`, `ui`, `update`, `util`, `viewmodel`), două distribuții (`play` / `direct`),
o politică de securitate centralizată și defensivă și o acoperire de testare solidă.

**Verdict general: calitate de producție bună, cu un singur blocaj critic de livrare.**

Punctele tari confirmate empiric:

- Compilarea Kotlin/Java reușește pe ambele flavor-uri (`direct`, `play`).
- **184 teste unitare JVM, 0 eșecuri, 2 ignorate** (run complet local, ambele variante).
- Politica de securitate este implementată consecvent (HTTPS-only pentru trafic public,
  excepție cleartext doar loopback/LAN privat controlată de varianta de build, fără redirect
  HTTPS→HTTP, dimensiuni maxime de răspuns, validare strictă URL/magnet, updater verificat
  prin SHA-256 + dimensiune exactă).
- Autentificarea locală folosește PBKDF2 cu 310.000 iterații, sare de 16 octeți, comparare
  constant-time și blocare progresivă; sesiunea persistentă este ștearsă la logout.
- WebView restrictiv (HTTPS-only, fără cookies, fără JavaScript, fără file access, Safe
  Browsing activ), magneturile sunt validate strict și cer confirmare explicită.

**Blocajul critic:** `scripts/verify-project.py` — garda care trebuie să treacă în CI și în
`scripts/package-source.py` — **eșuează** pe starea actuală a arborelui. Motivele sunt
cronologice: pe 28/08/2026 toolchain-ul a fost mutat la `AGP 9.5.0-alpha03` și Gradle
wrapper `9.7.1` (fără checksum SHA-256) fără a fi sincronizate garda, README-ul și
CHANGELOG-ul. În plus, un guardrail din `verify-project.py` verifică o frază
(`repeatOnLifecycle`) care a fost înlocuită intenționat în `MainActivity` cu o colecție
non-gated, motivată prin comentariu. Orice lansare (CI, arhivă sursă, release) este
blocată până la realiniere.

Mai există o **cheie TMDB reală** în `local.properties` din working copy și o serie de
**artefacte de lucru** la rădăcina proiectului (`$err`, `$log`, fișiere HTML, torrente,
logs de build, `.bat` cu căi hardcodate) care fie nu trebuie să ajungă într-o arhivă sursă,
fie trebuie șterse. Acestea nu apar într-un checkout CI curat, dar apar în arhiva livrată.

---

## 1. Scop și metodologie

### 1.1 Scop

Audit complet („professional audit”) al aplicației și al procesului de livrare:

1. Configurare și reproductibilitate build (Gradle, AGP, wrapper, variante, semnare, secrete).
2. Securitate (rețea, URL-uri, magnet, WebView, update, sesiune, TorrServer, stocare locală).
3. Calitate cod și arhitectură.
4. Testare (unitare, instrumentate, benchmark) și acoperire.
5. CI/CD (workflow-uri) și scripturi de livrare.
6. Documentație, legalitate, confidențialitate și conformitate Play.

### 1.2 Metodologie

- Revizuire statică completă a fișierelor sursă (`app/src/main`, `app/src/test`,
  `app/src/androidTest`, `benchmark`, `scripts`, `.github`, resurse, docs).
- Verificări empirice locale cu JDK-ul configurat (`JAVA_HOME` = Android Studio JBR 25,
  SDK 37 în `C:/Users/mark1/AppData/Local/Android/Sdk`):
  - `py -3 scripts/verify-project.py`
  - `.\\gradlew.bat :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest`
  - `.\\gradlew.bat :app:lintDirectDebug :app:lintPlayDebug` (încercat separat, vezi §8.4)
  - Inspecția rapoartelor de test (`build/test-results`), a APK-urilor precedente și a
    logurilor de build existente în arbore (8/12, 8/24, 8/29).
- Compararea documentației (README, CHANGELOG, rapoarte de reparație/optimizare) cu starea reală a codului.

### 1.3 Limitări

- Working copy **nu este repository Git** → nu a fost disponibil istoricul de commit-uri;
  datarea s-a făcut după `LastWriteTime`.
- Excutorul de shell are o limită de 30 s/comandă; `lint` a fost rulat ca sarcină programată
  separată; build-ul complet `assembleRelease` nu a fost reluat (există dovezi ale unui
  `BUILD SUCCESSFUL` pe 29/08/2026 în `opt-final.out.log` și un APK debug asamblat pe 31/08/2026).
- Nu s-a rulat pe hardware Android TV; testarea pe dispozitiv rămâne în sarcina operatorului.
- Auditul este de tip revizuire; nu este o dovadă de absență a vulnerabilităților.

---

## 2. Inventar

| Componentă | Dimensiune / structură |
|---|---|
| Module Gradle | `:app` (aplicație), `:benchmark` (macrobenchmark) |
| Variante (flavor) | `play` (Play-compatibil), `direct` (updater + LAN cleartext) |
| Build types | `debug`, `release` (minify+shrink), `benchmark` |
| Fișiere sursă `src/main` | 45 fișiere Kotlin + manifest/resurse |
| Fișiere test `src/test` | 24 clase JVM (fișierele JSON de contract pentru scraping) |
| Teste instrumentate | `AppSmokeTest`, `DistributionPolicySmokeTest` |
| Benchmark | `BaselineProfileGenerator`, `StartupJankBenchmark` + baseline profile seed |
| Scripturi | `verify-project.py`, `generate-sbom.py`, `package-source.py`, `bootstrap-dependency-verification.sh` |
| CI/CD | `.github/workflows/android-ci.yml`, `draft-release.yml`, `dependabot.yml` |
| Docs | README, CHANGELOG, PRIVACY, RELEASE_CHECKLIST, NOTICE, LICENSE, rapoarte RO, `docs/security`, `docs/performance` |

**Stack:** Kotlin 2.4.10, Compose BOM 2026.08.00, material3, Navigation Compose 2.9.8,
Lifecycle 2.11.0, Activity Compose 1.13.0, Retrofit 3.0.0, OkHttp 5.5.0, Okio 3.18.1,
Coil 3.5.0, coroutines 1.11.0, DataStore 1.2.1, Gson, org.json (teste), androidx.benchmark.
---

## 4. Constatări detaliate pe severitate

Severități: 🔴 Critică · 🟠 Înaltă · 🟡 Medie · ⚪ Scăzută/observație.

### 4.1 🔴 C1 — Garda de release `verify-project.py` eșuează (CI și packaging blocate)

Fișiere: `scripts/verify-project.py`, `gradle/libs.versions.toml`,
`gradle/wrapper/gradle-wrapper.properties`, `MainActivity.kt`.

- **Cronologie:** README/CHANGELOG (20–21/08) și verifier (20/08) declară `AGP 9.3.1`
  și Gradle `9.7.0` cu checksum. Pe 28/08/2026 `libs.versions.toml` a fost mutat la
  `AGP 9.5.0-alpha03`, iar wrapper-ul la `9.7.1` **fără** `distributionSha256Sum`; nu au
  fost actualizate garda, README-ul și CHANGELOG-ul.
- **Impact:** primul pas din `android-ci.yml` eșuează; `package-source.py` (care invocă
  verifier-ul) eșuează; `RELEASE_CHECKLIST.md` nu poate fi bifat.
- **Recomandare (alegeți o direcție și aplicați-o consecvent):**
  - **A** — revenire la toolchain-ul documentat: `AGP 9.3.1` + Gradle `9.7.0` cu `distributionSha256Sum`;
  - **B** — păstrare `AGP 9.5.0-alpha03` / Gradle `9.7.1`, cu **actualizare conștientă** a
    `verify-project.py` (regula anti-preview relaxată explicit), README, CHANGELOG,
    RELEASE_CHECKLIST și adăugarea checksum-ului pentru `gradle-9.7.1-bin.zip`.
    Un AGP `alpha` nu este recomandat pentru producție.

### 4.2 🔴 C2 — Guardrail `repeatOnLifecycle` ≠ implementarea actuală din `MainActivity`

Fișiere: `scripts/verify-project.py:206`, `MainActivity.kt:64–75`.

- Codul actual colectează `uiEvents` în `lifecycleScope.launch` **intenționat** (comentariu
  detaliat: evenimentul `ExitApp` la 2 minute după predarea către playerul extern trebuie
  primit în timp ce Activity este `STOPPED`; o colecție `repeatOnLifecycle(STARTED)` ar fi
  anulată).
- Verifier-ul cere în continuare șirul `repeatOnLifecycle(Lifecycle.State.STARTED)`.
- **Recomandare:** realiniați verifier-ul la contractul nou (ex.: prezența
  `lifecycleScope.launch` + `uiEvents.collect` + `PLAYBACK_EXIT_DELAY_MS`) **nu** invers —
  revenirea la `repeatOnLifecycle` ar rupe auto-exit-ul documentat.

### 4.3 🟠 H1 — Cheie TMDB reală prezentă în `local.properties` (working copy)

- `local.properties` → `TMDB_API_KEY=<REDACTED>` (valoarea reală a fost eliminată
  înainte de publicare pentru a nu scurge cheia).
- Fișierul este corect ignorat de Git și exclus de `package-source.py`, dar există în
  working copy cu o cheie reală; CHANGELOG documentează o scurgere anterioară exact pe
  această cale.
- **Recomandare:** presupuneți cheia compromisă; **rotiți-o** înainte de orice publicare;
  nu copiați working copy-ul direct într-o arhivă publică.

### 4.4 🟠 H2 — Wrapper Gradle fără checksum SHA-256

- `gradle/wrapper/gradle-wrapper.properties` → `distributionUrl=...gradle-9.7.1-bin.zip`,
  fără `distributionSha256Sum`, fără `validateDistributionUrl`, fără `networkTimeout`.
- Contrastează cu README/CHANGELOG care cer „pinned SHA-256 verified”.
- **Recomandare:** adăugați checksum-ul SHA-256 oficial pentru `gradle-9.7.1-bin.zip`
  (sau reveniți la 9.7.0 cu checksum) + setările de securitate recomandate.
### 4.5 🟠 H3 — AGP preview (`9.5.0-alpha03`) într-un „toolchain de producție”

- `gradle/libs.versions.toml:2` → `agp = "9.5.0-alpha03"`.
- README și CHANGELOG declară explicit „Stable production toolchain: …, AGP 9.3.1” și
  verifier-ul interzice preview-uri. Folosirea unui alpha pentru producție contrazice
  politica propriu a proiectului.
- **Recomandare:** decizie conștientă (vezi C1). Pentru producție: reveniți pe un release
  AGP stabil verificat.

### 4.6 🟠 H4 — Artefacte de lucru la rădăcina proiectului (igienă arhivă)

Fișiere care **nu** sunt excluse de `package-source.py` și ar ajunge în arhiva sursă:

- `$err` (58 KB erori de compilare), `$log` (log build eșuat 24/08),
- `build-app_out.txt`, `build-output.txt`, `build-error.txt`, `build-clean-test.*.log`,
  `build-test.*.log`, `gradle-all-tests.log`, `gradle-scraper-tests.log`, `gradle-test.log`,
  `gradle-opt-test.*.log`, `opt-*.{out,err}.log`, `opt-*.pid`,
- `dt_headers.txt`, `dt_to_search.html`, `est_search.html`, `pctmix.html`,
  `sample_est.torrent` (105 KB), `MovieTorrentSearchTV-gradle-tests`,
- `run-mts-tests.bat`, `run-scraper-tests.bat` (căi hardcodate `C:\Users\mark1\.mts-tools`),
- `.github/java-upgrade/20260404163845/logs/0.log`.

`verify-project.py` nu le detectează (verifică doar directoarele generate și fișierele de
secrete), deci trec „verificarea” și ajung în arhivă.
- **Recomandare:** ștergerea lor din working copy și extinderea excluderilor din
  `package-source.py` (`*.log`, `$err`, `$log`, `*.pid`, `*.bat`, HTML/torrent din rădăcină,
  `java-upgrade`).

### 4.7 🟠 H5 — `WikipediaMetadataProvider` creează un `OkHttpClient()` propriu fără politică

- `WikipediaMetadataProvider.kt:35` → `WikipediaMetadataProvider(client: OkHttpClient = OkHttpClient())`.
- În producție, `AggregatedTorrentViewModel.kt:74` injectează `NetworkManager.getOkHttpClient`
  (client securizat); totuși constructor-ul cu valoare implicită permite un client brut
  fără interceptorul de transport, fără limita de 16 MiB pe răspuns și fără dispatcher
  partajat. `fetchLocalizedTitle` citește `response.body.string()` **fără limită de
  dimensiune** (singura frână este timeout-ul de 6 s).
- **Risc:** scăzut (URL hardcodat HTTPS, erori tratate ca `null`), dar încalcă modelul
  „fiecare client HTTP trece prin NetworkManager”.
- **Recomandare:** eliminați constructor-ul default sau forțați clientul injectat și citiți
  corpul cu o limită explicită de octeți.

### 4.8 🟡 M1 — Documentație și starea reală devin în contradicție

- README (20/08) și CHANGELOG (21/08): „AGP 9.3.1 / Gradle 9.7.0 cu SHA-256”, față de starea
  reală (AGP `9.5.0-alpha03` / Gradle `9.7.1` fără checksum).
- `docs/security/README.md`: „SBOM generated from the committed Gradle lockfiles” — de fapt
  `generate-sbom.py` citește `libs.versions.toml`; SBOM-ul committat (`docs/security/
  dependency-sbom.cdx.json`, 494 componente) nu are `metadata.component.version`.
- `OPTIMIZATION_REPORT_2026-08-29_RO.md`: „zero warnings de compilare” — build-ul actual are
  2 warning-uri MainActivity; fișierul începe cu `A#` (typo de titlu).
- **Recomandare:** revizuire și sincronizare după decizia C1; regenerare SBOM.

### 4.9 🟡 M2 — API-uri deprecated în `MainActivity`

- `setOnSystemUiVisibilityChangeListener` + `SYSTEM_UI_FLAG_FULLSCREEN` (deprecated) —
  singurele warning-uri noi de compilare.
- **Recomandare:** înlocuire fără urgență (ex. `WindowInsetsControllerCompat` pe loop sau
  `OnApplyWindowInsetsListener`), într-o revizie dedicată.

### 4.10 🟡 M3 — Alte mici probleme de cod

- `util/Constants.kt`: `PLAYBACK_EXIT_DELAY_MS` este duplicat și neutilizat (utilizarea reală
  este în `AppViewModel`); `Constants.APP_VERSION`/`BUILD_NUMBER`/`TMDB_API_KEY` sunt folosite.
- `HtmlDetailScraper.kt:135`: elvis operator întotdeauna non-null (warning de compilare).
- `MovieViewModel.kt:427`: pragul de voturi eliminat doar pentru seriale (`voteCountGte = null`)
  — asimetrie deliberată, dar nedocumentată comparativ cu filmele.
- `MovieDetailsScreen.kt` folosește noul `LocalClipboard` (corect), dar `OPTIMIZATION_REPORT`
  menționa migrarea la 817:28 — codul actual folosește linia ~918; restul mesajului era corect.
- `MainActivity` comentarii de cod în română („Versiune Optimizată Ultra Profesional”) —
  consistență lingvistică a codului; minora.
- Backup complet dezactivat (`allowBackup=false` + excluderi în `backup_rules.xml` /
  `data_extraction_rules.xml`) — corect pentru date locale sensibile.

### 4.11 ⚪ L1 — Observații minore / proces

- `.gitignore` strict corect; `app/.gitignore` minimal (`/build`) — suficient.
- Testul live `EstrenosTorrentLiveIT` este `@Ignore` și scuipă `println` — util, dar
  analizați dacă nu cumva poluează rapoartele.
- `docs/performance/README.md` corect recomandă benchmark pe hardware real.
- Nu există `gradle/verification-metadata.xml` (verificarea dependențelor); `bootstrap-dependency-verification.sh` există pentru generare — de rulat în CI online.
- Fără lockfile-uri Gradle (decizie documentată în README §Verification) — reevaluați pentru
  reproductibilitate după ce toate configurațiile rezolvă.

### 4.12 ⚪ L2 — Conformitate și legalitate (confirmat)

- `LICENSE` = „All rights reserved” (proprietar), cu notă pentru componente terțe; `NOTICE`
  listează dependențele directe și licențele, cu instrucțiuni de regenerare.
- `PRIVACY.md` descrie corect datele stocate local, contactele de rețea și limitele;
  operatorul trebuie să-și adauge identitatea înainte de publicare (așa cum cere și
  `RELEASE_CHECKLIST.md`).
- Manifestul Play (`main`) nu are `REQUEST_INSTALL_PACKAGES`; varianta `direct` o are.
  `<queries>` explicit pentru TorrServer; fără `QUERY_ALL_PACKAGES`.
- Avertisment legal de nehosting de media prezent în README.
---

## 5. Evaluarea securității (domeniu cu domeniu)

| Domeniu | Verdict | Detalii |
|---|---|---|
| Transport (OkHttp) | ✅ | Interceptor central: HTTPS-only public, cleartext doar loopback/LAN privat (gated `ALLOW_LAN_CLEARTEXT`), `followSslRedirects=false`, timeout-uri, răspuns max 16 MiB, cache 32 MiB, fără DNS-rebinding (IP literal) |
| Rețea (NSC) | ✅ | Play: `base-config cleartextTrafficPermitted=false` + excepție loopback XML; Direct: `true` la bază + validare runtime strictă |
| URL-uri remote | ✅ | `RemoteUrlPolicy`: HTTPS, port 443, fără credențiale, allow-list host; upgrade opțional numai postere |
| Magnet | ✅ | `MagnetLinkValidator` strict (parse propriu, limite, *percent-decoding* valid, xt unic, trackere ≤32, părți ≤64) + confirmare UI separată |
| WebView | ✅ | HTTPS-only, fără JS/cookies/storage/file-access, Safe Browsing, block în `shouldInterceptRequest` + `shouldOverrideUrlLoading`, magnet doar cu gest |
| Update (direct) | ✅ | Manifest → `UpdateManifestUrlPolicy`; APK → `UpdateUrlPolicy` (github.com, `releases/download`, path strict); redirect revalidat per hop; SHA-256 + dimensiune exacte, comparare constant-time; descărcare în cache privat; installer doar `direct` |
| Acces local | ✅ | PBKDF2-HMAC-SHA256, 310k iterații, sare 16 B, verifier 256-bit, comparare constant-time, lockout exponențial (max 5 min), migrare legacy curată |
| Sesiune | ✅ | Policy pură (ceas monoton), blocare 2 min în background, sesiune persistentă ștearsă doar la logout, key legacy interzis |
| TorrServer | ✅ | `TorrserverEndpoint` strict (scheme, userinfo, path, query, fragment, DNS rebound), scan limitat (≤4 subneturi, ≤1024 hosturi, 20 s, semafor 32), probe `/echo` cu body limitat |
| Stocare | ⚠️ | Preferințe + Gson JSON cu limite de dimensiune, fără criptare la rest — acceptabil (verifier-ul nu conține secretul); `allowBackup=false` |
| Permisiuni | ✅ | Doar `INTERNET` (main); `REQUEST_INSTALL_PACKAGES` exclusiv direct; fără `QUERY_ALL_PACKAGES` |
| Backup | ✅ | `allowBackup=false` + excluderi totale (`backup_rules.xml`, `data_extraction_rules.xml`) |

### 5.1 Puncte slabe rămase (securitate)

1. `WikipediaMetadataProvider` cu client default și `body.string()` nelimitat (H5).
2. Wrapper fără checksum (H2) — integritatea lanțului de build.
3. Cheie TMDB în working copy (H1) — expunere operațională.
4. Fără *certificate pinning* — normal pentru un agregator multi-domeniu; notat pentru
   completitudine.
5. Verifier-ul PBKDF2 stă în `SharedPreferences` necriptate — `allowBackup=false` +
   `MODE_PRIVATE` atenuează riscul pe device.

---

## 6. Evaluarea testării

### 6.1 Unitare (JVM, rulare locală)

- **48 suite-uri, 184 teste, 0 eșecuri, 0 erori, 2 skipped** (ambele variante).
- Acoperire deosebită pe politici de securitate: URL (manifest, APK, download), magnet,
  cleartext LAN, endpoint Torrserver, session lock, matcher, series builder, Wikipedia,
  scrapere (cu fixture-uri/contracte JSON), playback Torrserver cu server HTTP local.

### 6.2 Instrumentate (neexecutate local — necesită device/emulator)

- `AppSmokeTest` (identitate + backup), `DistributionPolicySmokeTest` (permisiuni +
  cleartext vs BuildConfig). Scop corect; de rulat pe device.

### 6.3 Benchmark

- `:benchmark` cu `BaselineProfileGenerator` și `StartupJankBenchmark` (10 iterații,
  `CompilationMode.Partial`). Baseline profile-ul din `app/src/main/baseline-prof.txt` este
  un *seed* conservator marcat „regenerate before each release” — de făcut pe hardware real.

### 6.4 Găuri de acoperire (exemple, nu blocante)

- Nu există teste unitare pentru `UpdateInstaller` (verificare SHA-256/redirecții) — logică
  critică și pur testabilă cu un server local (pattern existent în
  `TorrserverPlaybackManagerTest`).
- Nu există teste pentru `AppViewModel` (update/sesiune/exit) — parțial acoperit de
  `SessionLockPolicyTest`.
- Nu există teste Compose instrumentate pentru `HomeScreen`/`MovieDetailsScreen` — de adăugat
  în etape ulterioare.
---

## 7. CI/CD și livrare

- `android-ci.yml`: checkout → setup-java 17 → verify-project → test direct+play → lint
  direct+play → assemble app+benchmark → SBOM → upload diagnostice. **Blocat astăzi la pasul
  verify-project** (C1/C2).
- `draft-release.yml`: `workflow_dispatch` cu tag; secrete numai din `secrets`/`vars`;
  keystore decodat în `runner.temp` (nu se commit); `validateProductionConfiguration` +
  `validateDirectProductionConfiguration`; `assemblePlayRelease` + `generateUpdateManifest`;
  verifică egalitatea `RELEASE_TAG` cu `appVersionName`; creează release draft cu APK-uri,
  checksum-uri și `update.json`. Design solid.
- `dependabot.yml`: gradle + github-actions săptămânal. După repararea C1, un PR care
  introduce un AGP preview va fi respins automat de gardă — comportament corect.
- `scripts/package-source.py`: arhivă deterministă (data fixă, moduri stabile), exclude
  corect directoarele generate și fișierele de secrete — dar **nu** exclude log-urile,
  HTML-urile, `.torrent`, `.bat` din rădăcină (H4).
- `scripts/generate-sbom.py`: determinist, citește `libs.versions.toml`; `metadata.component.version`
  hardcodat „2.0.0” — de sincronizat cu `appVersionName` (M1).
- `scripts/bootstrap-dependency-verification.sh`: generare `verification-metadata.xml` —
  de rulat în mediu online, apoi review la commit.

---

## 8. Note de mediu și reproductibilitate

### 8.1 Toolchain local

- `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` → JDK 25; `java` din PATH = JRE 8.
  Gradle folosește `JAVA_HOME`. `gradle-daemon-jvm.properties` (generat de IDE) este corect
  exclus de `package-source.py` și `.gitignore`.
- Build-ul documentat cere JDK 17; AGP 9.5.0-alpha03 + Gradle 9.7.1 au funcționat local cu
  JBR 25 și istoric pe CI cu Java 17.
- SDK 37 instalat, licențe acceptate.

### 8.2 Verificarea minimă de reproductibilitate (din checkout curat)

```bash
python3 scripts/verify-project.py        # azi: FAIL (C1/C2) — trebuie reparat
./gradlew :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintDirectDebug :app:lintPlayDebug
./gradlew :app:assembleDirectDebug :app:assemblePlayDebug
./gradlew :app:assembleDirectRelease :app:assemblePlayRelease
./gradlew :benchmark:assembleDirectBenchmark :benchmark:assemblePlayBenchmark
```

### 8.3 Recomandări de validare înainte de lansare

- Rulați `verify-project.py` pe un copy curat (fără directoarele generate).
- Regenerați SBOM și sincronizați `docs/security/`.
- Testați pe Android TV real: D-pad, blocare 2 min, restaurare proces-death, loopback
  cleartext (Play), LAN privat HTTP (Direct), updater verificat (Direct), absența
  installer/updater (Play).
- 16 KB page-size: nu există biblioteci native proprii; re-verificați la introducerea vreuneia.

### 8.4 Rezultatul lint în această sesiune

`lintDirectDebug` + `lintPlayDebug` → **BUILD SUCCESSFUL**, 0 erori, 65–66 warning-uri
(anexa A.1). Compilarea și testele trec; `lintVitalPlayRelease` a trecut istoric (29/08/2026).
Recomandare: revizuiți periodic coada de warning-uri lint; nu sunt blocante.
---

## 9. Plan de acțiune prioritar (recomandat)

> **Status 2026-09-03:** pașii 1, 2, 4, 5, 6, 7, 8 și 9 sunt **implementați și verificați**
> (vezi Anexa B). Pasul 3 a fost soluționat pe toolchain (AGP migrat la 9.4.0 stabil);
> rotirea cheii TMDB rămâne o acțiune operațională a operatorului. Pașii 10–12 rămân
> deschiși (10 necesită hardware TV fizic; procedura este documentată în
> `docs/performance/README.md`).

| # | Acțiune | Severitate | Efort | Responsabil |
|---|---|---|---|---|
| 1 | **Decizie toolchain + realiniere completă**: alegeți A (AGP 9.3.1 + Gradle 9.7.0 + checksum) sau B (AGP 9.5.0-alpha03 + Gradle 9.7.1 + checksum + relaxare explicită a gărzii); actualizați `verify-project.py`, README, CHANGELOG, RELEASE_CHECKLIST | 🔴 C1 | mic | Operator |
| 2 | **Reparați guardrail-ul `MainActivity`** în `verify-project.py` pentru colecția non-gated (intenționată) a evenimentelor UI | 🔴 C2 | foarte mic | Operator |
| 3 | **Rotiți cheia TMDB** din `local.properties`; nu o lăsați în nicio arhivă | 🟠 H1 | mic | Operator |
| 4 | **Adăugați `distributionSha256Sum`** pentru Gradle 9.7.1 (sau revenirea la 9.7.0) + `validateDistributionUrl` + `networkTimeout` | 🟠 H2 | foarte mic | Operator |
| 5 | **Curățați rădăcina** de artefacte ($err, $log, logs, HTML, torrent, .bat, java-upgrade) și extindeți excluderile `package-source.py` | 🟠 H4 | mic | Operator |
| 6 | **Închideți bucla HTTP în `WikipediaMetadataProvider`**: client injectat obligatoriu + limită de octeți la citire | 🟠 H5 | mic | Dev |
| 7 | Sincronizați documentația (README/CHANGELOG/docs-security/SBOM version) | 🟡 M1 | mic | Operator |
| 8 | Eliminați API-urile deprecated din `MainActivity` (`WindowInsetsControllerCompat`), eliminați `Constants.PLAYBACK_EXIT_DELAY_MS` neutilizat și elvis-ul din `HtmlDetailScraper` | 🟡 M2/M3 | mic | Dev |
| 9 | Adăugați teste `UpdateInstaller` (SHA-256/redirect cu server local) | ⚪ | mediu | Dev |
| 10 | Regenerați baseline profile pe hardware real; imediat înainte de fiecare release | ⚪ | mediu | Dev |
| 11 | Rulați `bootstrap-dependency-verification.sh` în mediu online și comiteți `gradle/verification-metadata.xml` revizuit | ⚪ | mic | Operator |
| 12 | După repararea C1: re-rulează lanțul complet CI + release workflow pe un branch curat | 🔴 | — | Operator |

---

## 10. Concluzie

Codul aplicației este **bine construit, defensiv din punct de vedere al securității și
bine testat** (184 teste verzi, 0 eșecuri). Principalele probleme nu țin de logica aplicației,
ci de **igiena și sincronizarea procesului de livrare**:

1. o gardă de release care a rămas în urma toolchain-ului și a unei schimbări intenționate
   de design → **blochează orice livrare**;
2. o cheie API reală și artefacte de lucru în working copy;
3. un wrapper Gradle fără checksum SHA-256.

Cu pașii 1–3 din planul de acțiune (toți au efort mic), proiectul revine într-o stare de
livrare verificabilă și reproductibilă.

---

## Anexa A — Rezultate tehnice (execuții)

### A.1 Lint (executat 2026-09-03, sarcină separată)

```
> Task :app:lintDirectDebug
> Task :app:lintPlayDebug
BUILD SUCCESSFUL in 4m 32s
57 actionable tasks: 6 executed, 51 up-to-date
```

- `lint-results-directDebug.html` → **0 erori, 66 warning-uri**
- `lint-results-playDebug.html` → **0 erori, 65 warning-uri**
- `abortOnError=true` ✅ (build-ul reușește); warning-urile nu blochează.

### A.2 Teste unitare

```
> Task :app:testDirectDebugUnitTest UP-TO-DATE
> Task :app:testPlayDebugUnitTest
BUILD SUCCESSFUL in 1m 34s
48 suite-uri | 184 teste | 0 eșecuri/erori | 2 skipped (EstrenosTorrentLiveIT)
```

### A.3 `verify-project.py` (working copy, 2026-09-03)

Eșuează din cauze C1/C2 + artefacte de working copy (detalii în §3.1).

### A.4 Fișiere auditate (principale)

`app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`,
`settings.gradle.kts`, manifestele main/direct/benchmark, NSC-urile, toate clasele din
`app/src/main` (45 fișiere), toți receptorii/update/WebView/security/playback/repository,
24 clase de test + 2 instrumentate, 2 benchmark-uri, 3 scripturi Python, 2 workflow-uri CI/CD,
docs și rapoarte.

---

*Raport generat pentru revizuirea din 3 septembrie 2026. Nu înlocuiește testarea pe hardware
Android TV și nici revizuirea unui specialist de securitate pentru publicare.*

---

## Anexa B — Reparații aplicate (2026-09-03, post-audit)

| ID | Reparație | Fișiere afectate | Stare |
|---|---|---|---|
| C1 | Verifier-ul fixează explicit AGP `9.5.0-alpha03` ca derogare documentată (în loc să interzică orice preview) și mută pin-ul wrapper-ului pe 9.7.1 | `scripts/verify-project.py`, `README.md` | ✅ |
| C1-final | **Derogarea a fost închisă**: toolchain migrat la AGP **9.4.0 (stabil)**; verifier-ul interzice din nou preview-urile și pin-ează explicit `agp = "9.4.0"` | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `scripts/verify-project.py`, `README.md`, `CHANGELOG.md` | ✅ |
| C2 | Guardrail-ul `MainActivity` verifică acum colecția intenționată `lifecycleScope` + `uiEvents.collect` (auto-exit la 2 min în background), nu `repeatOnLifecycle` | `scripts/verify-project.py` | ✅ |
| H2 | SHA-256 oficial al `gradle-9.7.1-bin.zip` (`acd53f1eda…804d20a`, confirmat pe gradle.org/release-checksums) + `validateDistributionUrl=true` + `networkTimeout=10000` | `gradle/wrapper/gradle-wrapper.properties` | ✅ |
| H4 | Artefacte șterse din rădăcină (`$err`, `$log`, `*.log`, `*.pid`, `*.bat`, HTML/torrent de probă, `.github/java-upgrade`); `package-source.py` + `verify-project.py` resping aceste pattern-uri | rădăcina, `scripts/package-source.py`, `scripts/verify-project.py` | ✅ |
| H5 | `WikipediaMetadataProvider` nu mai are constructor cu client implicit; citire cu limită dură de 1 MiB (`body.source()` + `readUpTo(MAX_PAYLOAD_BYTES)`); test actualizat | `WikipediaMetadataProvider.kt`, `WikipediaMetadataProviderTest.kt` | ✅ |
| M1 | Documentație sincronizată (toolchain, sursa SBOM); `generate-sbom.py` citește `appVersionName` din `app/build.gradle.kts`; SBOM comittat regenerat (v2.1.0, 27 componente) | `README.md`, `docs/security/README.md`, `scripts/generate-sbom.py`, `docs/security/dependency-sbom.cdx.json` | ✅ |
| M2 | API-uri deprecated înlocuite: controller obținut prin `WindowCompat.getInsetsController`, listener `systemUiVisibility` → `ViewCompat.setOnApplyWindowInsetsListener` | `MainActivity.kt` | ✅ |
| M3 | `Constants.PLAYBACK_EXIT_DELAY_MS` duplicat eliminat; elvis-ul inutil din `HtmlDetailScraper` curățat | `Constants.kt`, `HtmlDetailScraper.kt` | ✅ |
| #9 (plan) | **Teste `UpdateInstaller`** (9 teste): SHA-256 + dimensiune exactă, neconcordanțe declarate/streamed, redirect revalidat pe allow-list, respingere HTTP/metadata coruptă. `UpdateInstaller` refactorizat testabil (jumătatea download/verify separată de lansarea installer-ului; client de transport + validator URL injectabile, default-uri de producție neschimbate); `mockwebserver3` adăugat în catalog | `update/UpdateInstaller.kt`, `update/UpdateInstallerTest.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `scripts/verify-project.py` | ✅ |
| #10 (plan) | **Procedura de regenerare baseline profile** documentată (hardware TV fizic obligatoriu; `:benchmark:connectedBenchmarkAndroidTest` → `app/src/main/baseline-prof.txt`) | `docs/performance/README.md` | ✅ doc |

Guardrail-uri noi au fost adăugate în `verify-project.py` pentru fiecare reparație
(wrapper pin + checksum obligatoriu, derogarea AGP documentată, token-urile H5,
respingerea artefactelor de rădăcină), ca regresiile să fie blocate automat.

**Validare post-reparație (2026-09-03):**
- Suita completă pe ambele flavors (`testDirectDebugUnitTest` + `testPlayDebugUnitTest`):
  **202 teste, 0 eșecuri, 0 erori** (2 skip = `EstrenosTorrentLiveIT` `@Ignore`),
  inclusiv `UpdateInstallerTest` (9/9 pe fiecare flavor, cu server HTTP loopback) și
  `WikipediaMetadataProviderTest` cu clientul injectat explicit.
- `verify-project.py` pe arborele curățat: trece cu excepția by-design a
  `local.properties` (guardrail-ul refuză corect o working copy cu secrete populate —
  ștergerea/rotirea cheii rămâne decizia operatorului, vezi H1 din §3).
- SBOM regenerat după migrarea AGP: v2.1.0, 28 componente.
