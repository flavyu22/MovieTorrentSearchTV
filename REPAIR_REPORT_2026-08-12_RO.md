# Raport de reparație completă — MovieTorrentSearchTV 2.0.0

Data: 12 august 2026

## Rezumat

Aplicația a fost reparată cap-coadă și **validată printr-un build Gradle real** pe această
mașină (spre deosebire de reviziile anterioare, care nu aveau toolchain disponibil).
Toate verificările trec: compilare pe toate variantele, teste unitare, lint și gardul de
securitate al sursei (`verify-project.py`).

## Mediu de build (lipsa complet — instalat de la zero)

| Componentă | Stare |
|---|---|
| JDK 17 (Temurin 17.0.20+8) | instalat (`~/.mts-tools/jdk-17.0.20+8`) |
| Android cmdline-tools 22.0 | instalate |
| Platforma `android-37.0` | instalată |
| Build-Tools `37.0.0` | instalate |
| Platform-Tools `37.0.1` | instalate |
| Licențe SDK | acceptate |
| Gradle 9.5.0 (wrapper, SHA-256 verificat) | funcțional |

## Defecte reparate în această revizie

1. **Teste scraper nefuncționale off-device (`org.json` nemockuit).**
   `PirateBayScraper` și `EztvScraper` folosesc clasele Android `org.json.*`, care pe JVM
   aruncă "not mocked". S-a adăugat dependența reală `org.json:json:20250517`
   (`testImplementation`), astfel încât parser-ele de producție sunt exercitate real în
   testele locale. Acoperire: `ScraperContractFixtureTest`.

2. **Playback TorrServer blocat pentru server pe același dispozitiv.**
   `TorrserverEndpoint.normalize` respingea tot cleartext-ul în flavor-ul Play, inclusiv
   loopback (`127.0.0.1`). Traficul loopback nu părăsește dispozitivul, deci este sigur.
   S-a introdus `isLoopbackHost` (127.0.0.0/8, ::1, localhost) care este permis în ambele
   flavor-uri; cleartext-ul LAN rămâne în continuare blocat în Play (`ALLOW_LAN_CLEARTEXT=false`).
   Acoperire: `TorrserverPlaybackManagerTest.addsEphemerallyAndStreamsOnlyAConfirmedMediaFile`.

3. **Postere din istoric vechi respinse în loc să fie actualizate la HTTPS.**
   `validatedPosterUrl` respingea URL-urile `http://` ale posterelor. S-a adăugat parametrul
   `upgradeCleartext` în `RemoteUrlPolicy.allowlistedHttps` (implicit `false` = strict).
   Politica generică rămâne strictă; doar helper-ul de postere (`validatedPosterUrl`) face
   upgrade `http→https` pe același host, apoi aplică aceleași reguli (doar port 443, fără
   credențiale, doar hosturi allow-list). Acoperire: `PosterUrlPolicyTest`,
   `RemoteUrlPolicyTest` (ambele comportamente).

4. **`gradle-wrapper.properties` deteriorat de sincronizarea IDE.**
   Android Studio a rescris fișierul la Gradle 9.6.1 și a șters checksum-ul SHA-256.
   S-a restaurat pin-ul la Gradle 9.5.0 cu `distributionSha256Sum` și setările de securitate
   (`validateDistributionUrl`, `networkTimeout`). Build reproductibil restaurat.

5. **`gradle-daemon-jvm.properties` (generat de IDE) polua sursa.**
   Fișierul forța JVM 25 (inconsistent cu toolchain-ul documentat JDK 17) și hash-urile
   foojay din el erau semnalate ca posibile token-uri de către scanner. A fost eliminat din
   sursă, adăugat în `.gitignore` și în lista de excluziuni din `scripts/package-source.py`.

## Funcție nouă: surse în limba spaniolă (linkuri magnet)

La cerere, aplicația agregă acum și surse în limba spaniolă care expun linkuri magnet:

- **MejorTorrent**, **EliteTorrent**, **GranTorrent**, **DivxTotal** (alături de **DonTorrent**,
  deja existent).
- Implementare comună (`SpanishMagnetScraper`) peste un parser partajat
  (`parseMagnetSearchHtml`) care extrage fiecare magnet distinct și îi rezolvă titlul din
  textul ancorei sau din parametrul `dn` al magnetului — deci nu depinde de layout-ul
  fiecărui site. Titlurile primesc automat limba (ES / Multi), calitatea și sezonul/episodul.
- Toate sursele folosesc exclusiv HTTPS; orice eroare de rețea sau schimbare de domeniu
  produce o listă goală fără a afecta celelalte surse (agregatorul continuă).
- Acoperire cu teste noi (`SpanishMagnetScraperTest`, 3 teste): citirea titlului din ancora
  și din `dn`, deduplicarea pe info-hash, decodarea sigură a numelui.

## Rezultatele verificărilor

| Verificare | Rezultat |
|---|---|
| `:app:testPlayDebugUnitTest` | **52/52 trec** |
| `:app:testDirectDebugUnitTest` | **52/52 trec** |
| `:app:assemble` (play/direct × debug/release/benchmark) | **BUILD SUCCESSFUL** — 6 APK |
| `:app:compilePlayReleaseKotlin` + `compileDirectReleaseKotlin` | **BUILD SUCCESSFUL** |
| `:app:lintPlayRelease` + `:app:lintDirectRelease` | **BUILD SUCCESSFUL** (`abortOnError=true`) |
| `verify-project.py` pe sursă curată | **passed (125 fișiere)** |

## Note pentru publicare (rămân în sarcina operatorului)

- Release-urile sunt produse **nesemnate**; semnarea necesită keystore-ul real
  (`ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`).
- `TMDB_API_KEY` trebuie furnizată prin `local.properties` sau variabilă de mediu.
- `local.properties`, `.gradle/`, `.idea/`, `build/` sunt artefacte locale, excluse de
  `package-source.py`; `verify-project.py` trece pe un checkout curat (cum este arhiva).
