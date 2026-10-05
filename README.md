> Current provider selection: YTS, EZTV, TPB, Solid, TorrentsCSV, plus Rutracker when `RUTRACKER_API_KEY` is configured. BitSearch, Nyaa and Rutor were removed — Rutor's host is gone (HTTP 451). See PROVIDERS_CURRENT_RO.md for additions, removals and validation.

# MovieTorrentSearchTV 2.1.0

Android/Google TV catalogue browser designed for remote-control navigation. The project provides catalogue discovery, voice search, a persistent local **My List**, playback history, optional TorrServer integration on a trusted LAN, external-player handoff, multilingual UI, and a locked local profile.

> The application does not host media. Distributors and users are responsible for using only lawful sources and for complying with service terms in their jurisdiction.

## Professional Android TV improvements

- Adaptive poster grid for 720p, 1080p and 4K TV layouts.
- D-pad-first focus restoration when returning from details.
- Persistent **My List** with add/remove controls in the details header.
- Selected-title restoration after Android process death.
- Automatic low-RAM profile that reduces poster preloading and cache dimensions.
- Two-minute background session lock; an authenticated session is remembered on the device so a cold start does not ask for the credential again. Only a manual **Logout** re-locks the profile.
- The app fully exits on its own two minutes after a movie starts playing (external player handoff).
- Separate `play` and `direct` distributions:
  - `play`: no package-installer permission, no self-updater, HTTPS only.
  - `direct`: verified self-updater and optional cleartext TorrServer only on loopback/private LAN endpoints.
- Verified production toolchain: JDK 17, Android SDK 37, AGP 9.4.0 (stable) and Gradle 9.7.1 with a pinned SHA-256 wrapper checksum.

## Local setup

Requirements: JDK 17, Android SDK 37, and the checked-in Gradle wrapper.

Create or update the generated root `local.properties` file, which is ignored by Git:

```properties
sdk.dir=/your/android/sdk/path
TMDB_API_KEY=CHANGE_ME_TMDB_V3_API_KEY
```

Protected configuration can alternatively be supplied through `~/.gradle/gradle.properties` or environment variables. Priority is Gradle property, environment variable, then root `local.properties`.

- `TMDB_API_KEY`: TMDB v3 API key. It is injected into the APK and therefore must be treated as a client-side key that can be extracted, restricted, monitored and rotated.
- `UPDATE_MANIFEST_URL`: direct distribution only; HTTPS URL ending in `update.json`.
- `UPDATE_REPOSITORY`: direct distribution only; GitHub repository in `owner/repository` form.

Never commit a populated secrets file. The TMDB key exposed in the original 1.9.2 archive must be revoked before publishing any repaired build.

## Verification

Fast repository and packaging guardrails:

```bash
python3 scripts/verify-project.py
```

Full verification from a clean checkout:

```bash
./gradlew \
  :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest \
  :app:lintDirectDebug :app:lintPlayDebug \
  :app:assembleDirectDebug :app:assemblePlayDebug \
  :app:assembleDirectRelease :app:assemblePlayRelease \
  :benchmark:assembleDirectBenchmark :benchmark:assemblePlayBenchmark
```

Generate the declared-dependency CycloneDX inventory and create a deterministic secret-free source archive:

```bash
python3 scripts/generate-sbom.py
python3 scripts/package-source.py
```

The repository intentionally does not ship the pre-flavor Gradle lockfiles from 1.9.2. Regenerate dependency locks and Gradle verification metadata only after all Play/Direct configurations resolve successfully in the final Android CI environment.

## Build variants

Debug APKs:

```bash
./gradlew :app:assemblePlayDebug
./gradlew :app:assembleDirectDebug
```

Outputs:

- `app/build/outputs/apk/play/debug/app-play-debug.apk`
- `app/build/outputs/apk/direct/debug/app-direct-debug.apk`

Production releases are unsigned unless all signing environment variables are present. The direct update manifest includes APK size and SHA-256; the app downloads to private cache, verifies both values and only then opens Android's package installer.

## TorrServer security

The Play variant requires HTTPS for every public request. The single cleartext exception in both variants is loopback, so an on-device TorrServer stays usable; that traffic never leaves the device and must still be confirmed on real hardware. The Direct variant additionally permits HTTP only when runtime validation confirms loopback, link-local or RFC1918/ULA private addressing. Redirect targets are revalidated. Public cleartext HTTP is blocked.

## Distribution

Review `PRIVACY.md`, `NOTICE`, `LICENSE`, `RELEASE_CHECKLIST.md`, `BUILD_VERIFICATION_2.0.0_RO.md` and `REPAIR_REPORT_2.0.0_RO.md` before publishing. Keep the application ID and release signing key stable after the first production release.
