# Provideri activi — 19 septembrie 2026

Sunt active cinci surse: YTS, EZTV, TPB, Solid și TorrentsCSV. Rutracker se înregistrează
doar când `RUTRACKER_API_KEY` este configurată, deci cinci sau șase în funcție de build.

## De ce lista a fost scurtată

Fiecare provider este interogat la fiecare deschidere a ecranului de detalii, o dată pentru
fiecare variantă de titlu pe care ViewModel-ul o construiește (principal, localizat,
original). Prin urmare, un provider în plus **multiplică** numărul de cereri concurente, nu
se adaugă la el. Înainte de această schimbare erau 9 provideri, adică aproximativ 30-36 de
cereri HTTP concurente la o deschidere.

- **BitSearch** — eliminat pentru lentoare. API-ul public are cotă și limitare de rată,
  suprapune catalogul lui Solid (deci deduplicarea existentă după hash arunca oricum
  majoritatea rezultatelor) și era cel mai puțin fiabil contributor, cu 0.85.
- **Nyaa** — eliminat: index exclusiv pentru anime. Era interogat pentru fiecare film și
  fiecare serial, returnând nimic pentru marea majoritate, cu rânduri lente și fără peeri
  activi (proba proprie a raportat 0 seederi).
- **Rutor** — eliminat: gazda nu mai există. Verificat live pe 2026-10-03,
  `http://rutor.info/...` răspunde **HTTP 451 Unavailable For Legal Reasons**, iar conexiunea
  HTTPS este închisă forțat de gazdă la mijlocul handshake-ului TLS. În aplicație fiecare
  căutare eșua cu „Network error" după ~0,6s, deci cheltuia o cerere și o intrare în lista
  de erori pentru a nu livra nimic, permanent. Clasa scraperului și testele ei de parser
  rămân în sursă; doar înregistrarea a dispărut.
- **Rutracker** — înregistrare condiționată. Fără cheie, arunca `IOException` la fiecare
  apel, cheltuind o cerere și introducând permanent eroarea „API key not configured" în
  lista de erori. Clasa scraperului și testele ei sunt păstrate; doar înregistrarea
  necondiționată a dispărut. `isConfigured` a devenit public pentru asta.

Clasele `BitSearchScraper` și `NyaaScraper` rămân în sursă, împreună cu testele lor, și
pot fi reintroduse în `defaultScrapers()` dacă situația se schimbă.

## Surse multi-limbă adăugate

Ambele aduc magnet-uri cu titlu în limbi non-Latin, completând acoperirea care exista
doar în engleză și spaniolă.

- **Rutor** (`rutor.info`): index în limba rusă cu endpoint JSON public, fără cheie.
  Interogarea este `GET /search/{interogare}/0/0/0`. Răspunsul conține `torrent_id`
  (hash validat cu `[a-f0-9]{40}`), dimensiune, seeders și leechers. Magnetul este
  reconstruit local din hash validat, niciodată preluat verbatim din payload. Linkul de
  detaliu este acceptat doar dacă este HTTPS pe aceeași gazdă.
- **Rutracker** (`rutracker.org`): trackerul rus de referință, cu API JSON publică
  documentată (`/api/v2.0/index.php`, metoda `search`). **Necesită o cheie**, furnizată
  prin `local.properties`, variabila de mediu sau `~/.gradle/gradle.properties` cu numele
  `RUTRACKER_API_KEY`. Fără cheie, providerul raportează o eroare explicită („API key not
  configured") și nu întoarce fals zero rezultate. Învelișurile de eroare ale API-ului
  (`error_code` într-un răspuns HTTP 200) sunt tratate ca eșec, nu ca rezultat gol.

Titlurile în chirilică sunt transportate prin UTF-8 și clasificate de `detectLanguage`
care recunoaște deja `RU`; nu a fost necesară modificarea tabelului de limbi.

## Provideri existenți

Au fost adăugate anterior:

- **BitSearch**: API HTTPS public, căutare după titlu, filtre filme/seriale. Proba „Big Buck Bunny” a returnat HTTP 200 și 5 rezultate relevante cu hashuri valide; unele raportează seederi. Documentație: https://bitsearch.eu/api . API-ul public are cotă de utilizare; erorile HTTP sunt raportate, nu mascate ca rezultate goale. Catalogul poate suprapune rezultate Solid; deduplicarea existentă după hash rămâne activă.
- **Nyaa**: RSS HTTPS pentru categoria anime. Proba „Sintel” a returnat HTTP 200 și un rezultat cu hash valid. Rezultatul raportase 0 seederi: căutarea și generarea magnetului funcționează, disponibilitatea descărcării nu a fost demonstrată. Parserul respinge declarații XML DTD/ENTITY și linkuri externe nepermise.

Ambele integrări folosesc cereri anulabile și limite de răspuns, validează hashurile și omit rândurile fără titlu. Căutarea IMDb nu este declarată pentru aceste două indexuri; aplicația folosește căutarea după titlu.

Validare: compilare izolată Kotlin/JVM trecută; 32 teste JVM trecute; 8 verificări cu parserele reale pe răspunsuri capturate trecute. Testele noi verifică inclusiv construirea cererilor, magneturile, categorii nesuportate și răspunsuri invalide. Dovezi în audit/provider-additions-2026-09-19. Guardrail static verificat la împachetare.

1337x, Torrentio și EstrenosTorrent rămân eliminate conform cererii precedente. BTDig nu a fost adăugat după HTTP 502; Internet Archive nu a fost integrat deoarece a fost verificată doar căutarea, nu întregul flux torrent.

Configurația locală originală și reparațiile anterioare sunt păstrate. Versiunea internă rămâne 2.1.0 / 10. Arhiva conține surse, nu un APK nou. Buildul Android complet și redarea pe televizor nu au fost validate. Testele JVM folosesc o adnotare Compose Immutable substituită numai pentru compilare, fără substituirea logicii providerilor. Funcționarea indexării nu garantează seederi, redare sau disponibilitate în orice rețea.

Rapoartele anterioare sunt istorice; acest document descrie selecția curentă.
