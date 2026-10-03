# Verificare și reparare provideri — MovieTorrentSearchTV

**19 septembrie 2026. 37 teste JVM trecute, 9 verificări cu parserele Kotlin reale trecute. Niciun provider șters: nu există dovezi suficiente că unul este definitiv nefuncțional și nereparabil.**

Acest raport continuă auditul inițial. În această etapă au fost autorizate și efectuate modificări în implementările providerilor. Comparația „18 fișiere nemodificate” din raportul inițial descrie exclusiv etapa precedentă, nu această versiune.

## Rezultate observate

| Provider | Verificare executată | Concluzie și acțiune |
|---|---|---|
| YTS | Căutarea „Big Buck Bunny” a primit HTTP 200, status=ok și zero filme, atât pe yts.gg, cât și pe movies-api.accel.li. yts.lt, yts.bz și yts.ag au redirecționat către yts.gg. Alte cereri au expirat sau au primit 403. | API accesibil intermitent; căutarea fără rezultate nu dovedește defect. Păstrat; reparată concurența mirror-urilor în scraper. Extragerea unui rezultat pozitiv YTS nu a fost confirmată live. |
| EZTV | Endpointul oficial de catalog: HTTP 200; un rezultat, cu magnet/hash valid; parserul real a extras un rezultat. | API și parsare confirmate pentru răspunsul testat. Păstrat. Căutarea după titlu simplu nu este oferită de API; integrarea folosește IMDb. |
| The Pirate Bay / TPB | Căutare „Big Buck Bunny”: HTTP 200, 22 rezultate cu hash valid; toate conțin termenii titlului; parserul real extrage 22. | Căutare și parsare confirmate pentru proba testată. Păstrat. |
| SolidTorrents | Aceeași căutare: HTTP 200, 20 rezultate relevante cu hash valid; parserul real extrage 20. | Căutare și parsare confirmate. Păstrat; erorile JSON nu mai sunt tratate ca index gol. |
| TorrentsCSV | Aceeași căutare: HTTP 200, un rezultat relevant cu hash valid; parserul real extrage unul. | API și parsare confirmate. Pagina web a afișat separat Anubis/Access Denied, ceea ce nu a împiedicat această probă API. Păstrat. |
| 1337x | Domeniul redirecționează la www.1337xx.to. Pagina de căutare a oferit 20 linkuri de detalii; o pagină de detalii a oferit magnet valid. Parserele reale le extrag. Însă rezultatele pentru titlul testat nu au fost relevante. | Funcționare parțială: transport și extragere confirmate, relevanță neconfirmată. Folosit direct hostname-ul canonic pentru a evita redirectul. Păstrat; nu este declarat sănătos integral. |
| Torrentio | Manifestul public poate fi citit prin accesul web; cererea HTTP directă la manifest și cererea stream pentru IMDb tt1254207 au primit 403. | Acces blocat în mediul testat; cauza exactă nu este stabilită. Nu dovedește închiderea serviciului. Păstrat; reparată interpretarea răspunsurilor JSON incomplete. |
| EstrenosTorrent | Pagina de căutare: HTTP 200, token CSRF prezent și extras de parserul real. Cererea POST de căutare a eșuat cu „Tunnel connection failed: 403 Forbidden”. | Formular accesibil, dar integrarea completă nu este verificabilă din acest mediu. Eroarea indică tunelul de acces, nu o dovadă că providerul este închis. Păstrat. |

Aceste rezultate sunt probe punctuale, nu garanții de disponibilitate permanentă. Nu au fost descărcate filme sau episoade; au fost citite răspunsuri JSON/HTML. Pentru 1337x, existența magnetului a fost verificată pe pagina returnată de motor, fără lansarea lui.

Nu au fost adăugate mirror-uri necunoscute, nu au fost relaxate regulile HTTPS și nu s-a încercat ocolirea răspunsurilor 403 sau a protecțiilor anti-bot. Configurația privată originală este păstrată în arhiva personală, identică byte cu byte.

## Reparații efectuate

1. **YTS — mirror lent:** scraperul încerca domeniile secvențial. Un domeniu lent putea consuma întregul timeout de 15 secunde al agregatorului înainte să fie încercată alternativa. Cererile concurează acum; primul răspuns valid câștigă, restul sunt anulate. O eroare individuală nu anulează celelalte opțiuni. Un răspuns valid gol rămâne un rezultat valid gol. Timeoutul exterior și propagarea anulării sunt păstrate. Catalogul principal folosea deja concurență și nu a fost modificat.
2. **HTML/mirror-uri — succes pierdut:** HtmlDetailScraper putea obține rezultate de la un mirror, apoi arunca eroarea reținută de la un mirror anterior. Rezultatul câștigător are acum prioritate.
3. **1337x — redirect inutil:** se folosește direct `https://www.1337xx.to`, destinația HTTPS observată. Calea de căutare și parserul sunt păstrate. Aceasta reduce un pas de rețea; nu repară relevanța motorului extern.
4. **Torrentio și TorrentsCSV — răspuns invalid ascuns:** lipsa câmpului `streams`, respectiv `torrents`, produce eroare de sursă în loc de „zero rezultate”. Listele goale explicite rămân valide.
5. **Solid — răspuns de eroare ascuns:** `success=false` sau lipsa `results` produce eroare de sursă, nu succes gol.
6. **EZTV — JSON gol interpretat ca succes:** dacă lipsește lista, este necesar un `torrents_count` explicit egal cu zero; `{}` nu mai este acceptat drept răspuns sănătos.

Providerii temporar inaccesibili nu sunt eliminați automat. Eliminarea pe baza unui timeout, 403, zero rezultate ori blocaj regional ar putea distruge o integrare care funcționează pe televizorul utilizatorului.

## Validare

- Compilare izolată JVM a surselor scraper-elor și a dependențelor lor: **trecută**, folosind Kotlin 2.4.20, coroutines 1.11.0, OkHttp 5.5.0 și versiunile declarate în proiect.
- **37 teste JUnit trecute**, inclusiv cele cinci teste noi din ProviderRecoveryTest: mirror lent/anulare, eșec urmat de succes, rezultat gol valid, toate mirror-urile eșuate și JSON invalid.
- **9 verificări ale răspunsurilor capturate cu parserele reale:** YTS primar, YTS alternativ, EZTV, TPB, Solid, TorrentsCSV, linkuri 1337x, magnet 1337x și token EstrenosTorrent — toate trecute.
- Singurul substitut folosit în compilarea izolată este adnotarea Compose `Immutable`, fără comportament executabil. Codul scraper-elor, modelele, clientul HTTP, coroutines și parserele JSON sunt cele reale. Nu este un build Android și nu testează UI-ul sau NetworkManager pe dispozitiv.
- Guardrail-ul proiectului: trecut pe sursa curată, înainte de reincluderea configurației private originale.
- APK nou, test pe TV, disponibilitate prin rețeaua utilizatorului și redare completă: **nevalidate**.

Dovezile sunt incluse în `audit/providers-2026-09-19/`: rezultate HTTP, rezultatele testelor, lista claselor testate și patch-ul acestei etape. Fișierele HTML brute și tokenurile CSRF nu sunt incluse. Auditul inițial este păstrat separat ca istoric.

## Verificarea finală pe Windows / Android TV

```powershell
.\gradlew.bat :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest :app:assembleDirectDebug :app:assemblePlayDebug --stacktrace
```

Pe TV trebuie urmărite în special: YTS când primul domeniu răspunde lent; căutări relevante prin 1337x; Torrentio cu IMDb valid; cererea POST EstrenosTorrent; diferența dintre „sursă indisponibilă” și „zero rezultate”. Nu recomand ștergerea permanentă a ultimilor doi înainte de proba din rețeaua utilizatorului.

## Surse oficiale consultate

- [API EZTV](https://eztvx.to/api/) — endpoint și căutare IMDb.
- [API SolidTorrents](https://solidtorrents.eu/api) — structura JSON; serviciul documentează limite de cereri, deci o limitare temporară nu implică dispariția providerului.
- [Manifest Torrentio](https://torrentio.strem.fun/manifest.json) — serviciul publică resursa stream; acest fapt nu confirmă accesul stream din mediul nostru.
