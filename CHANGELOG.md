# Changelog

## Unreleased — 2026-10-04

### Changed

- **The active provider set was trimmed from nine to six** to cut movie-details latency. Every
  provider is queried on each detail screen once per title variant the ViewModel builds
  (primary, localized, original), so an extra provider *multiplies* the concurrent request
  count rather than adding one — the previous set cost roughly 30-36 concurrent HTTP requests
  per screen. `BitSearch` was dropped (public quota and rate limits, its catalogue overlaps
  Solid so the existing per-infohash dedup discarded most of it anyway, lowest reliability at
  0.85) and `Nyaa` was dropped (anime-only index, queried for every film and series, mostly
  returning nothing, and its own probe reported 0 seeders). Films now query YTS, TPB, Solid
  and TorrentsCSV; series query EZTV, TPB, Solid and TorrentsCSV.
- **`Rutor` was removed because its host is gone.** Verified live 2026-10-03:
  `http://rutor.info/...` returns **HTTP 451 Unavailable For Legal Reasons** and the HTTPS
  connection is forcibly closed by the remote host mid-TLS-handshake. In-app it failed
  every search with "Network error" after ~0.6s, spending a request and a permanent
  source-error entry to deliver nothing. `RutorScraper` and its parser tests are kept in the
  tree, so it can be restored if the index ever returns.
- **Every remaining provider was verified to answer a live query** on 2026-10-03: YTS
  (`/api/v2/list_movies.json`, 1 match), EZTV (`/api/get-torrents`, 30 torrents),
  TPB/apibay (`/q.php`, many rows with valid infohashes), Solid (40 `/torrent/` links in
  the search HTML) and TorrentsCSV (`/service/search`, 25 rows).
- `Rutracker` is now registered **only when `RUTRACKER_API_KEY` is configured**. Unconfigured it
  threw on every call, which spent a request per search and pushed a permanent "API key not
  configured" entry into the source-error list. `RutrackerScraper.isConfigured` was made public
  so the registration can be conditional. The scraper class and its tests are unchanged and the
  provider comes back automatically once a key is supplied.
- Audit M1/M3 (build hygiene): the two remaining compiler warnings were removed, so
  `compileDirectDebugKotlin` is warning-free and the OPTIMIZATION_REPORT's "zero
  warnings" claim holds again. `ScraperUtils.formatTwoDecimals` now uses
  `roundToLong()` instead of `Math.round(value * 100).toLong()` (both round half-up,
  and torrent sizes are non-negative, so the rendered size is unchanged), and
  `YtsScraper.withoutTrailingYear` dropped two safe calls on a non-null
  `String.replace` result. A new `ScraperUtilsTest` case pins the two-decimal output,
  the zero-padded fraction and every unit threshold so the rounding change is locked.
- Audit H4: the stale build logs that had accumulated in the project root
  (`apk-out.log`, `test-out.log`, `e2.log`, `t2.log` and their `-err` counterparts)
  were deleted. `package-source.py` already excluded `*.log`, but they were
  polluting the working copy.
- Audit guard fix: the KDoc in `MultiSourceScraper` documenting the Rutor removal
  spelled out its URL with the `http` scheme, which `verify-project.py` reads as a
  cleartext-traffic violation in production source. The host is now named without
  the scheme, so the guard no longer fires on a comment while keeping its severity
  for real endpoints.
- `BitSearchScraper` and `NyaaScraper` remain in the source tree with their tests, so either can
  be restored by adding one line back to `defaultScrapers()`.
- Removed the duplicated empty-result branch: `isEmptyResult` and `!isLoading` rendered
  byte-identical markup. The now-unused `isEmptyResult` parameter was dropped from the results
  column.

### Fixed

- **The published `update.json` shipped a stale SHA-256, which broke the in-app updater for
  every Direct-flavour install.** The manifest committed at the repository root carried the
  digest of a superseded build (`8c7eed3c…`) while the APK actually attached to the release
  hashes to `dd77ec15…`. `AppViewModel.checkForUpdates` correctly rejects a manifest whose
  digest does not match the downloaded asset, so a user polling for updates would download
  the new APK and then discard it — surfacing as an updater that never succeeds, with no
  actionable error. The manifest now carries the digest and byte length of the APK the
  release really serves. `verify-project.py` gained a guard that cross-checks the committed
  manifest against the staged release directory (sha256 *and* `sizeBytes`) and fails the
  build on a mismatch, so the two can no longer drift apart unnoticed. The script also now
  fails when the root manifest is missing entirely, which is the other way this silently
  breaks: the raw URL would 404 and every check would fail closed.
- **`.gitignore` no longer leaks machine-specific files into the repository.** The IDE
  project directory (`.idea/`, including per-developer run configurations, Gradle sync state
  and the deployment-target selector) and CPython bytecode caches (`__pycache__/`, `*.pyc`)
  are now ignored in full. Previously only five individual `.idea` paths were listed, so
  every new IDE version added another tracked-but-machine-specific file, and running the
  `scripts/` helpers left `.pyc` files showing up as untracked noise.
- **`YtsScraper` now recovers from YTS's year-suffixed-query degradation.** When `query_term`
  ends in a 4-digit year the endpoint answers **HTTP 200 with `status="ok"` and a non-zero
  `movie_count`, but omits the `movies` array entirely** (verified live 2026-10-03 on every
  configured mirror). The movie-details screen appends the year to the title it queries with,
  so YTS silently looked like a dead provider on most titles. Two changes:
  - `parseYtsResponse` treats a missing `movies` array as a **valid empty page** instead of
    throwing. It is not a transport or protocol failure, and reporting it as a network error
    put a bogus "Network error" entry in the source-error list. Genuine failures still
    surface: a non-`ok` `status`, a missing `data` object and malformed JSON all still throw
    `IOException`.
  - `searchDomains` retries once **without the trailing year** when the first attempt came
    back empty (`"Dune Part Two 2024"` → `"Dune Part Two"`), so the year-appended query
    resolves to real rows. The retry is bounded to two attempts, is skipped entirely when the
    first query already returned rows, and never rewrites an IMDb-id lookup
    (`searchByImdb` still sends `tt15239678` verbatim).
- Movie details: the trailing **copy magnet** and **open source page** controls are now real
  focus targets. They were `Box(Modifier.clickable)` nodes whose icon tint was driven by the
  *row's* focus state, so moving D-pad focus onto them turned the row dark while the button
  itself never highlighted — focus landed on an invisible control and the selected action could
  not be identified. Each button now owns its `MutableInteractionSource` and paints a white disc
  with a dark glyph when focused (no animation, so no extra per-row interpolator on entry-level
  TVs).
- Movie details: the row no longer sets `semantics(mergeDescendants = true)`, which folded the two
  action buttons into the row's own description and made them unreachable for a screen reader.
  The spoken description moved to the text block, leaving the actions as separate buttons.
- Movie details: focus recovery after a search no longer parks on the back button when the
  active filters hid every result. That case renders the filter-reset state, whose only button
  now receives focus.
- Movie details: the sort option is resolved by index instead of by comparing localized label
  strings. Two options sharing a label in some translation previously selected the wrong order,
  and the label list was rebuilt on every recomposition, re-triggering the open menu's
  scroll-to-item effect on each frame.
- Movie details: the trailing **copy magnet** and **open source page** controls were in fact
  unreachable with a D-pad, so the fix that gave them their own focus indicator never took effect
  in practice. Each action already owned a `MutableInteractionSource` and painted its own white
  disc, but both were children of the clickable row, and Compose's directional focus search only
  offers focus to a target lying outside the focused node's bounds — RIGHT therefore had no
  destination and focus stayed on the row. Confirmed on the emulator: the node reported as
  `focused` kept the row's bounds across three RIGHT presses, while DOWN and LEFT moved normally.
  The row is now a non-clickable visual `Surface` and the play action moved onto the text block, so
  the actions sit outside the focus target's bounds. D-pad-right now reaches each action, LEFT
  returns to the row, and the pill keeps its shape, border and colours.

### Added

- Movie details now surfaces release data that every scraper already parsed but this screen
  discarded: **peers (leechers)**, **upload day** (`yyyy-MM-dd`, UTC) and **audio language**.
  Leechers matter because a release with no seeders can still be playable; the day separates a
  fresh 2160p WEB-DL from a stale CAM rip at a glance.
- Movie details shows a result-provenance line above the list — results and the search
  duration — plus a `+N` count of rows hidden by the active filters. It reuses
  `resultsSummary`, which was already translated into all nine supported locales but was
  never rendered. The line's source count (`3/8 sources`) was subsequently removed: the
  provider total is an implementation detail of `defaultScrapers()`, it changed with every
  provider trim, and reporting how many indexes were configured told the user nothing they
  could act on. The two `%d` placeholders were dropped from `resultsSummary` in all nine
  locales, and the now-unused `SearchStats.totalSources`/`activeSources` fields were removed.
- New distinct empty state when sources answered but the quality/language filters excluded every
  row: it explains the filter and offers an **All** reset. It previously blamed the providers with
  "no sources found", and its retry button re-ran the same search with the same filters, which
  could not clear the state.
- `peers`, `uploadedOn`, `torrentRowDescription` and `noResultsForFilter` added to `AppStrings`
  with translations for all nine locales (RO, EN, IT, ES, FR, DE, PT, RU, EL). The row's spoken
  description uses positional placeholders because its arguments mix `String` and `Int`.

## Unreleased — 2026-09-19

### Added

- Two multilingual magnet providers, bringing Cyrillic/Russian results to a set that was
  previously English- and Spanish-only:
  - `RutorScraper` (`rutor.info`) — keyless public JSON index. The magnet is always
    rebuilt locally from a validated `[a-f0-9]{40}` `torrent_id`; the remote detail URL is
    surfaced only when it is a same-host HTTPS URL.
  - `RutrackerScraper` (`rutracker.org`) — documented public JSON API. Requires a key,
    supplied through the existing protected-configuration chain as `RUTRACKER_API_KEY`
    (Gradle property → environment variable → `local.properties`). Without a key the
    provider reports an explicit error instead of silently returning zero results, and
    `error_code` envelopes returned with HTTP 200 are treated as failures.
- `MultilingualProvidersTest` — 8 JVM tests covering request construction, Cyrillic
  parsing, magnet reconstruction, unsupported categories, missing API key, API error
  envelopes and foreign/cleartext/non-numeric link rejection.

### Performance

- Hoisted four per-call `Regex` compilations out of hot loops: the whitespace pattern in
  `ScraperUtils.stripTags()` (runs once per scraped anchor), the info-hash pattern in
  `BitSearchScraper.parseResponse()` (once per result row), the Nyaa view-URL pattern
  (once per row) and the two title-cleaning patterns in `MovieViewModel.cleanTitle()`.
  These previously recompiled the pattern on every invocation during live searches.

## Unreleased — 2026-09-03

### Changed

- Audit follow-up (C1 resolved): migrated the toolchain from the `9.5.0-alpha03`
  derogation to the stable **AGP 9.4.0** release (requires Gradle ≥ 9.6.0; the
  pinned 9.7.1 wrapper satisfies it). Preview versions are forbidden again by
  `verify-project.py`, which now pins the stable toolchain.

### Added

- Audit follow-up: `UpdateInstallerTest` — JVM tests for the verified updater's
  download/verify pipeline against a loopback `mockwebserver3` server: SHA-256 and
  exact-size enforcement, declared/streamed size mismatches, allow-list-scoped
  redirect revalidation, HTTP failure and malformed-metadata rejection.
- `UpdateInstaller` is now testable without the Android framework: the
  download/verify half was split from installer launch, and the transport client
  plus URL validator are injectable internal hooks (production defaults unchanged).

### Security

- Audit H2: pinned the official SHA-256 of `gradle-9.7.1-bin.zip`
  (`acd53f1eda…804d20a`, cross-checked against gradle.org/release-checksums) in the
  wrapper and enabled `validateDistributionUrl` + `networkTimeout`.
- Audit H5: `WikipediaMetadataProvider` no longer creates its own implicit
  `OkHttpClient()` — a policy-enforced client must be injected — and the Wikidata
  payload is now read with a hard 1 MiB byte cap instead of an unbounded
  `body.string()`.

### Fixed

- Audit C1: `verify-project.py` no longer contradicts the actual toolchain; the AGP pin and the
  Gradle wrapper pin (9.7.1, mandatory checksum) are asserted against the versions actually
  declared. Superseded further down this release by the migration from the `9.5.0-alpha03`
  derogation to stable AGP 9.4.0.
- Audit C2: the `MainActivity` guardrail now checks the intentional plain
  `lifecycleScope` collection of `uiEvents` (the two-minute auto-exit must fire while
  the activity is STOPPED behind the external player) instead of demanding
  `repeatOnLifecycle`, which would have broken the documented auto-exit behavior.
- Audit H4: removed stray build/debug artifacts from the repository root (`$err`,
  `$log`, `*.log`, `*.pid`, sample HTML/torrent captures, hardcoded test `.bat`
  helpers, `.github/java-upgrade` logs); `package-source.py` and `verify-project.py`
  now reject these patterns so they can never reach a source archive again.
- Audit M1: documentation realigned with reality (toolchain statement, SBOM source of
  truth); `generate-sbom.py` now reads the app version from `app/build.gradle.kts`
  instead of a hardcoded `2.0.0`.
- Audit M2: replaced the deprecated `setOnSystemUiVisibilityChangeListener` /
  `SYSTEM_UI_FLAG_FULLSCREEN` immersive-mode handling in `MainActivity` with
  `ViewCompat.setOnApplyWindowInsetsListener` + `WindowInsetsCompat` (zero new
  compile warnings).
- Audit M3: removed the duplicate, unused `Constants.PLAYBACK_EXIT_DELAY_MS` (the
  authoritative constant lives in `AppViewModel`).

## Unreleased — 2026-08-21

### Security

- Removed the real TMDB v3 API key that had leaked back into a source snapshot via
  `local.properties`. The file is gone from the tree; the SDK resolves through
  `ANDROID_HOME` and the key must be supplied again (rotated!) via `TMDB_API_KEY`
  in a fresh `local.properties`, `~/.gradle/gradle.properties` or the environment.
- Replaced the broad `QUERY_ALL_PACKAGES` permission with explicit `<queries><package>`
  entries for the two supported TorrServer apps (Google Play policy compliance).
  The only package-visibility API used is `getLaunchIntentForPackage`.

### Removed

- Stray third-party web pages (`detail.html`, `tomadivx.html`, `tomaform.html`) and an
  empty `solidnet.json` from the repository root; no code referenced them.
- Generated IDE/build cache directories (`.gradle/`, `.idea/`, `.kotlin/`, `build/`,
  `app/build/`, `.artifacts/`) so `scripts/verify-project.py` passes on the tree.

### Changed

- `NOTICE` now lists the license of every direct dependency declared in the version catalog.

## Unreleased — 2026-08-20

### Changed

- The authenticated session is now persisted on the device. On cold start the app no longer asks for the username/password again if the profile was unlocked previously; the credential is requested again **only after a manual Logout** (or a credential reset). The persisted session flag holds no secret material — the PBKDF2 verifier-only credential storage is unchanged.
- The application now closes itself completely **two minutes after a movie starts playing** (external player handoff). If you return to the app within those two minutes, the auto-exit is cancelled and the app stays open; otherwise it exits on its own.
- `verify-project.py` session guardrail realigned with the new behavior: the legacy `is_logged_in` key is still forbidden, and the checks now require the persisted session to be cleared on logout.

## 2.1.0 — 2026-08-18

### Added

- Persistent **search history** (up to 12 recent queries): focusable TV chips on the Home screen re-run a previous search with the active filters; a dedicated chip clears the history. Stored locally only.
- **Sort menu** in the title details torrent list (date, seeds, quality, size, source). The `AggregatedTorrentViewModel.SortOption` engine already existed; it is now reachable from the D-pad UI.
- **Copy magnet link** action on every torrent row, with clipboard confirmation toast. Useful for TorrServer setups and external download managers.
- Automatic TorrServer discovery: the loopback endpoint is probed at startup in every distribution; the Direct distribution additionally performs a bounded local-subnet scan until a server is configured.

### Security

- Restored the documented session posture: a cold start always requires the local credential again. The short-lived persisted session flag is removed from storage on first launch of this revision, and returning to the app after the two-minute background window now locks the session even if the background exit job did not fire.
- Play builds keep HTTPS-only public traffic but now permit cleartext loopback for an on-device TorrServer (runtime policy and a loopback-scoped network-security domain exception). All non-loopback cleartext hosts remain rejected.

### Changed

- Dependency refresh to the latest stable releases: Compose BOM 2026.08.00, OkHttp 5.5.0, Okio 3.18.1, compose-shimmer 1.5.0, org.json 20260814 (tests), UIAutomator 2.4.0.
- Gradle wrapper 9.5.0 → 9.7.0 (pinned SHA-256 verified).
- AGP 9.3.1, Kotlin 2.4.10, Lifecycle 2.11.0, Activity 1.13.0, Navigation 2.9.8, Retrofit 3.0.0, Coroutines 1.11.0, Coil 3.5.0 and DataStore 1.2.1 verified as the newest stable releases.
- Launcher icon converted from a 968 KB PNG to WebP (~125 KB) to shrink every APK.
- Removed the unused TLS pre-warm hooks (`NetworkManager.prewarm`, `MultiSourceScraper.prewarm`) and their metadata host list; no caller existed and launch-time requests stay opt-in.

### Fixed

- Source guardrails (`verify-project.py`) realigned with the 2.1.0 toolchain: version pin, Gradle 9.7.0 wrapper pin, TorrServer LAN exception list and a strengthened no-persistent-session check that also covers the renamed session key.
- Signed-release workflow now derives the required release tag from `appVersionName` instead of a hardcoded tag, so releases are producible again for the current version.
- Deterministic source packaging now excludes Python bytecode caches (`__pycache__`, `*.pyc`).

## 2.0.0 repair revision — 2026-08-07

### Fixed

- TMDB text searches now honor genre, rating and year filters consistently for both movies and series.
- Catalogue page arguments are clamped to the supported 1–500 range, preventing invalid/overflow page requests.
- Android TV filter popups now close competing menus reliably; Rating can no longer remain open behind Genre or the main menu.
- Activity UI-event collection is lifecycle-aware and no longer remains active while the Activity is stopped.
- Low-memory detection now also checks the runtime memory class, not only the OEM `isLowRamDevice` flag.
- Detail posters use smaller decode targets and no crossfade on constrained televisions to reduce heap pressure and frame drops.
- The SBOM generator now accepts an explicit output path, avoiding unnecessary repository build artifacts during external verification.

## 2.0.0 — 2026-08-05

### Added

- Persistent multilingual **My List** for Android TV.
- Add/remove favorite action in title details.
- Adaptive TV poster grid and low-RAM image-preload profile.
- Process-death restoration for the selected title and collection mode.
- `play` and `direct` distribution flavors with independent security capabilities.
- Session-lock policy unit tests and distribution-policy instrumentation tests.
- Deterministic secret-free source packaging command.

### Security

- Removed the exposed TMDB credential and all generated Gradle/IDE caches from the distributable project.
- Cold starts no longer restore a persisted authenticated state.
- Added a two-minute background lock and cancellation of pending external magnet requests.
- Public cleartext HTTP is rejected centrally; only private LAN TorrServer endpoints are eligible in the Direct flavor.
- Self-update and `REQUEST_INSTALL_PACKAGES` are unavailable in the Play flavor.
- Remote URLs require HTTPS on port 443, no embedded credentials and an allowlisted host.

### Changed

- Stable toolchain: AGP 9.3.1, Gradle 9.5.0, JDK 17, compile/target SDK 37.
- Android TV banner normalized to 320 × 180.
- Preferred-player intent handling migrated to `IntentCompat`.
- CI and release workflows now build and validate both distribution flavors.
- Removed stale pre-flavor Gradle lockfiles and replaced the old lockfile-based SBOM with a deterministic version-catalog inventory.

### Fixed

- Invalid Gradle Kotlin DSL in the update-manifest task.
- Collection restoration accidentally falling through to an online catalogue search.
- Selected-title loss after Android process recreation.
- Fixed seven-column layout that scaled poorly across television resolutions.
