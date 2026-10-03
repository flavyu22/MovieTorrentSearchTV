# Changelog

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

- Audit C1: `verify-project.py` no longer contradicts the actual toolchain; AGP
  9.5.0-alpha03 is pinned explicitly as a documented derogation (Gradle wrapper pin
  moved to 9.7.1 with mandatory checksum).
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
