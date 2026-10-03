# Raport de Optimizare — 2026-08-29 (v2.1.0)

## Context
Audit complet de performanță peste întreaga aplicație (UI Compose, ViewModels,
scrapere, rețea, build). Codebase-ul era deja optimizat substanțial (cache-uri,
chei stabile în liste, dispatcher-e limitate, regex-uri precompilate în majoritatea
locurilor); acest pas elimină hotspot-urile rămase, măsurabile și verificabile.

## Modificări aplicate

### 1. `model/UnifiedTorrent.kt` — hotspot major de CPU în sortări (CRITIC)
- **Problemă:** `qualityScoreFor()` compila **7 obiecte `Regex` noi la fiecare apel**.
  Getter-ele `sizeInBytes`, `qualityScore` și `uploadTimeMillis` erau apelate de
  comparatorii din `AggregatedTorrentViewModel.filterAndSort()` de **O(n·log n) ori
  per reîmprospătare a listei** → mii de compilări de regex + creări de
  `SimpleDateFormat` la fiecare sortare (ex. sortarea implicită DATE_DESC crea până
  la 4 `SimpleDateFormat` per comparație).
- **Soluție:**
  - Toate cele 7 pattern-uri ridicate ca `private val` precompilate în companion
    (inputul este deja uppercased, deci semantica e identică fără `IGNORE_CASE`).
  - Cele 3 proprietăți derivate sunt acum memoizate per instanță cu `by lazy`
    (sigur: `UnifiedTorrent` nu este niciodată serializat Gson — doar in-memory).
- **Impact:** sortarea listelor de torrente trece de la zeci de mii de compilări
  de regex la câte o parsare memoizată per element; latența UI pe TV scade vizibil
  la listele mari (până la ~800 rezultate agregate).

### 2. `util/TorrentMatcher.kt` — lookup O(n) → O(1)
- **Problemă:** `RELEASE_NOISE` (~70 tokeni) era `List`; testul de apartenență din
  bucla fierbinte `countNonTitleExtrasBefore` făcea scanare liniară per token.
- **Soluție:** convertit la `Set` (singura utilizare era `in`, deci fără schimbare
  de comportament).

### 3. `ui/screens/MovieDetailsScreen.kt` — alocări în recompoziție
- **Problemă:** header-ul de detalii se recompune la fiecare tick de progres al
  căutării; `String.format(Locale.US, "%.1f", rating)` era executat de 2 ori per
  recompoziție (semantics + text).
- **Soluție:** `val ratingText = remember(rating) { ... }` — o singură formatare
  per valoare de rating.

### 4. `app/build.gradle.kts` — APK de release mai mic
- Adăugat `androidResources.localeFilters += setOf("en","ro","it","es","fr","de","pt","ru","el")`
  (API-ul modern AGP 9.x; `resourceConfigurations` este deprecated), exact cele 9
  locale suportate de aplicație (`Translations` + `locales_config.xml`).
- **Impact:** elimină din APK/AAB sutele de traduceri ale bibliotecilor (material3,
  coil, androidx) pentru limbi pe care aplicația nu le oferă → binar mai mic,
  fără nicio schimbare de comportament pentru limbile suportate.

### 5. `ui/screens/MovieDetailsScreen.kt` — zero warnings de compilare
- Migrat de la `LocalClipboardManager` (deprecated) la noul API `LocalClipboard` +
  `ClipEntry`/`ClipData` cu scriere suspendată pe `rememberCoroutineScope`, eliminând
  ultimul warning de depreciere din build.

## Ce NU a fost modificat (auditat, deja optim)
- Liste lazy cu chei stabile peste tot (`gridItems(key = { it.id })`,
  `itemsIndexed(key = { source_infoHash })`).
- Coil: cache mem/disc dimensionat după `memoryClass`, RGB_565 pe low-RAM,
  fetch/decode cu paralelism limitat, crossfade dezactivat în grile.
- OkHttp: client unic partajat, pool 8/5min, limite de 16 MiB per răspuns,
  fără downgrade TLS la redirect.
- Toate regex-urile scraperelor: deja precompilate în companion objects.
- StateFlow cu generații atomice anti-race, cache API `LruCache`, debounce
  prin anularea generațiilor vechi.

## Verificare
- `:app:testPlayDebugUnitTest` + `:app:assemblePlayRelease` rulate offline
  (JUnit: `UnifiedTorrentTest`, `TorrentMatcherTest` etc. validează comportamentul
  neschimbat al codului refactorizat).
