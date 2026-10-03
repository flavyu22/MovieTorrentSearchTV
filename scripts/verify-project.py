#!/usr/bin/env python3
"""Repository-level release guardrails for MovieTorrentSearchTV."""
from __future__ import annotations

import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []

TEXT_SUFFIXES = {
    ".gradle", ".kts", ".kt", ".java", ".xml", ".json", ".toml",
    ".properties", ".md", ".yml", ".yaml", ".txt", ".pro", ".sh", ".py",
}
GENERATED_DIRS = {".gradle", ".idea", ".kotlin", "build", ".artifacts", ".externalNativeBuild", ".cxx"}
LOCAL_FILES = {"local.properties", "keystore.properties", "secrets.properties", ".env"}
SECRET_ASSIGNMENT = re.compile(
    r"(?im)^\s*(?:TMDB_API_KEY|ANDROID_KEYSTORE_PASSWORD|ANDROID_KEY_PASSWORD|"
    r"ANDROID_KEYSTORE_BASE64)\s*=\s*(?!CHANGE_ME(?:\b|_)|replace_with_|\$\{|\s*$)([^#\s]{12,})"
)
TMDB_TOKEN = re.compile(r"(?i)\b[a-f0-9]{32}\b")


def fail(message: str) -> None:
    ERRORS.append(message)


def iter_source_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        parts = path.relative_to(ROOT).parts
        if any(part == ".git" or part in GENERATED_DIRS for part in parts):
            continue
        if path.suffix.lower() in TEXT_SUFFIXES or path.name in {"gradlew", ".gitignore", "LICENSE", "NOTICE"}:
            files.append(path)
    return files


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="utf-8", errors="replace")


def png_dimensions(path: Path) -> tuple[int, int] | None:
    try:
        data = path.read_bytes()[:24]
        if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            return None
        return struct.unpack(">II", data[16:24])
    except OSError:
        return None


files = iter_source_files()
for path in files:
    rel = path.relative_to(ROOT).as_posix()
    text = read(path)
    # The guardrail script itself must mention the demo id to be able to detect it.
    if "com.example.movietorrentsearchtv" in text and rel != "scripts/verify-project.py":
        fail(f"Demo application id remains in {rel}")
    if SECRET_ASSIGNMENT.search(text):
        fail(f"Possible committed secret assignment in {rel}")
    # Exclude fixture hashes, checksums and documentation from generic TMDB-like token checks.
    # Exclude fixture hashes, checksums and documentation from generic TMDB-like token checks.
    # gradle-daemon-jvm.properties is machine-generated (updateDaemonJvm), never packaged,
    # and its foojay Disco ids are 32-hex strings that would false-positive the token scan.
    if path.name == "gradle-daemon-jvm.properties":
        continue
    if path.suffix in {".properties", ".kts"} and TMDB_TOKEN.search(text):
        fail(f"Possible embedded API token in {rel}")

for path in ROOT.rglob("*"):
    rel_parts = path.relative_to(ROOT).parts
    if path.is_dir() and any(part in GENERATED_DIRS for part in rel_parts):
        fail(f"Generated/cache directory present: {path.relative_to(ROOT).as_posix()}")
    if path.is_file() and path.name in LOCAL_FILES:
        fail(f"Local secret/configuration file must not be packaged: {path.relative_to(ROOT).as_posix()}")
    if path.is_file() and path.name.endswith("gradle.lockfile"):
        fail(
            "Stale Gradle lock state must not be packaged; regenerate it only after "
            f"resolving every Play/Direct variant: {path.relative_to(ROOT).as_posix()}"
        )
    # Audit H4: stray build/debug artifacts must never reach a source archive.
    if path.is_file():
        name = path.name
        rel_file = path.relative_to(ROOT).as_posix()
        if name in {"$err", "$log", "MovieTorrentSearchTV-gradle-tests"} or name.endswith(
            (".log", ".pid", ".torrent")
        ):
            fail(f"Stray build/debug artifact must not be packaged: {rel_file}")
        if path.suffix in {".html", ".bat", ".out", ".txt"} and len(rel_parts) == 1 and path.name not in {"gradlew.bat"}:
            fail(f"Stray root-level artifact must not be packaged: {rel_file}")

required = [
    "LICENSE",
    "NOTICE",
    "PRIVACY.md",
    "CHANGELOG.md",
    "BUILD_VERIFICATION_2.0.0_RO.md",
    "gradle.properties.example",
    "app/src/main/res/xml/locales_config.xml",
    "app/src/main/res/xml/network_security_config.xml",
    "app/src/direct/res/xml/network_security_config_direct.xml",
    "app/src/direct/AndroidManifest.xml",
    "app/src/test/java/io/github/flavyu22/movietorrentsearchtv/update/UpdateInstallerTest.kt",
]
for rel in required:
    if not (ROOT / rel).is_file():
        fail(f"Required project file missing: {rel}")

main_manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
direct_manifest = read(ROOT / "app/src/direct/AndroidManifest.xml")
for token in (
    'android:allowBackup="false"',
    'android:localeConfig="@xml/locales_config"',
    'android:usesCleartextTraffic="false"',
    "androidx.core.content.FileProvider",
    "android.intent.category.LEANBACK_LAUNCHER",
    '<uses-feature android:name="android.software.leanback" android:required="true" />',
):
    if token not in main_manifest:
        fail(f"Main manifest security/TV configuration missing: {token}")
if "android.permission.REQUEST_INSTALL_PACKAGES" in main_manifest:
    fail("Google Play-compatible main manifest must not request package installation")
for token in (
    "android.permission.REQUEST_INSTALL_PACKAGES",
    'android:networkSecurityConfig="@xml/network_security_config_direct"',
    'android:usesCleartextTraffic="true"',
):
    if token not in direct_manifest:
        fail(f"Direct manifest configuration missing: {token}")

main_network = read(ROOT / "app/src/main/res/xml/network_security_config.xml")
direct_network = read(ROOT / "app/src/direct/res/xml/network_security_config_direct.xml")
if '<base-config cleartextTrafficPermitted="false">' not in main_network:
    fail("Main network security config must deny cleartext by default")
# The only permitted cleartext scope is the loopback on-device TorrServer exception.
if main_network.count('cleartextTrafficPermitted="true"') > 1:
    fail("Main network security config permits cleartext beyond the loopback exception")
if 'cleartextTrafficPermitted="true"' in main_network and (
    '<domain-config cleartextTrafficPermitted="true">' not in main_network
    or ">localhost</domain>" not in main_network
):
    fail("Main network cleartext exception must be a loopback-scoped domain-config")
if '<base-config cleartextTrafficPermitted="true">' not in direct_network:
    fail("Direct network config must explicitly declare its LAN cleartext capability")

app_build = read(ROOT / "app/build.gradle.kts")
for token in (
    'val appVersionName = "2.1.0"',
    'create("play")',
    'create("direct")',
    'buildConfigField("boolean", "ENABLE_SELF_UPDATE", "false")',
    'buildConfigField("boolean", "ALLOW_LAN_CLEARTEXT", "false")',
    'buildConfigField("boolean", "ENABLE_SELF_UPDATE", "true")',
    'buildConfigField("boolean", "ALLOW_LAN_CLEARTEXT", "true")',
    "JavaVersion.VERSION_17",
    "warningsAsErrors = false",
):
    if token not in app_build:
        fail(f"Build/distribution configuration missing: {token}")

versions = read(ROOT / "gradle/libs.versions.toml")
# Toolchain policy: stable AGP releases only. The 9.5.0-alpha03 derogation from the
# 2026-09 audit ended once AGP 9.4.0 (stable, requires Gradle >= 9.6.0) verified
# end-to-end across all Play/Direct configurations; preview versions are forbidden
# again. Bump the pin deliberately after re-verifying a newer stable release.
if re.search(r'(?im)^agp\s*=\s*"[^"]*(?:alpha|beta|rc)', versions):
    fail("Android Gradle Plugin must not use a preview version for production")
if re.search(r'(?im)^agp\s*=\s*"9\.4\.0"$', versions) is None:
    fail("AGP must stay pinned to the verified stable 9.4.0 toolchain")
wrapper = read(ROOT / "gradle/wrapper/gradle-wrapper.properties")
if "gradle-9.7.1-bin.zip" not in wrapper:
    fail("Gradle wrapper is not pinned to 9.7.1")
checksum = re.search(r"(?m)^distributionSha256Sum=([a-f0-9]{64})$", wrapper)
if checksum is None:
    fail("Gradle wrapper SHA-256 checksum is missing or malformed")

remote_policy = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/util/RemoteUrlPolicy.kt")
for token in ("!parsed.isHttps", "parsed.port != 443", "parsed.username.isNotEmpty()", "parsed.password.isNotEmpty()"):
    if token not in remote_policy:
        fail(f"Remote URL policy guard missing: {token}")

network_manager = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/di/NetworkManager.kt")
for token in ("BuildConfig.ALLOW_LAN_CLEARTEXT", "isPrivateNetworkHost", "Cleartext HTTP is restricted to private TorrServer endpoints"):
    if token not in network_manager:
        fail(f"Central network policy guard missing: {token}")

app_view_model = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/viewmodel/AppViewModel.kt")
for token in (
    "SessionLockPolicy",
    "onAppBackgrounded",
    "onAppForegrounded",
    "LEGACY_LOGGED_IN_KEY",
    "shouldLockOnForeground",
):
    if token not in app_view_model:
        fail(f"Session lock implementation missing: {token}")
if 'putBoolean("is_logged_in", true)' in app_view_model or 'putBoolean(LEGACY_LOGGED_IN_KEY, true)' in app_view_model:
    fail("Legacy authenticated session key must never be written")
# A persisted session is an intentional, user-selected feature so the local profile is not
# re-locked on every launch. The persisted flag must still be cleared on a manual logout so
# logging out always re-locks the profile on the next start.
if ".remove(LOGGED_IN_SESSION_KEY)" not in app_view_model:
    fail("Persistent authenticated session must be cleared on logout")

movie_view_model = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/viewmodel/MovieViewModel.kt")
for token in (
    "loadFavoritesMovies",
    "toggleFavorite",
    "persistSelectedMovie",
    "isFavoritesMode",
    "MAX_CATALOGUE_PAGE = 500",
    ".filter { Mapper.matchesTmdbFilters(it, state, true) }",
    ".filter { Mapper.matchesTmdbFilters(it, state, false) }",
):
    if token not in movie_view_model:
        fail(f"Android TV catalogue/collection guard missing: {token}")


main_activity = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/MainActivity.kt")
# Audit C2: uiEvents is collected in a plain lifecycleScope launch on purpose — the
# two-minute ExitApp event arrives while this activity is STOPPED behind the external
# player, so a repeatOnLifecycle(STARTED)-gated collector would be cancelled before
# receiving it. Guard the actual (documented) contract instead.
for token in (
    "lifecycleScope.launch",
    "appViewModel.uiEvents.collect",
    "AppViewModel.UiEvent.ExitApp",
    "finishAffinity",
):
    if token not in main_activity:
        fail(f"MainActivity background exit-event collection guard missing: {token}")

home_screen = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/ui/screens/HomeScreen.kt")
if "manager?.memoryClass ?: 256" not in home_screen:
    fail("Home poster preloading must detect constrained memoryClass devices")
if "showQuality = false; showRating = false" not in home_screen:
    fail("Home filter menus must close competing rating popups")

movie_details = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/ui/screens/MovieDetailsScreen.kt")
for token in ("detailPosterWidthPx", ".crossfade(!isLowRamDevice)"):
    if token not in movie_details:
        fail(f"Low-RAM detail poster optimization missing: {token}")

app_strings = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/model/AppStrings.kt")
for token in ("myList =", "clearMyList =", "addToMyList =", "removeFromMyList =", "myListEmpty ="):
    if app_strings.count(token) != 9:
        fail(f"Translation completeness mismatch for {token.rstrip(' =')}: expected 9 languages")

update_installer = read(ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/update/UpdateInstaller.kt")
for token in ("MessageDigest.getInstance(\"SHA-256\")", "expectedSizeBytes", "FileProvider.getUriForFile"):
    if token not in update_installer:
        fail(f"Verified updater guard missing: {token}")

lan_exceptions = {
    "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/repository/TorrserverRepository.kt",
    "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/viewmodel/TorrserverViewModel.kt",
    # Loopback auto-discovery and discovered-server fallback addresses.
    "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/viewmodel/MovieViewModel.kt",
}
for path in files:
    rel = path.relative_to(ROOT).as_posix()
    if path.suffix == ".kt" and rel.startswith("app/src/main/java/") and "http://" in read(path) and rel not in lan_exceptions:
        fail(f"Unexpected cleartext URL in production Kotlin source: {rel}")


sbom_script = read(ROOT / "scripts/generate-sbom.py")
for token in ("tomllib", "libs.versions.toml", "declared version-catalog dependencies"):
    if token not in sbom_script:
        fail(f"Declared-dependency SBOM generator guard missing: {token}")

# Audit H5: the Wikipedia metadata provider must use the shared, policy-enforced HTTP
# client (no implicit default OkHttpClient) and read its payload with a hard byte cap.
wikipedia_provider = read(
    ROOT / "app/src/main/java/io/github/flavyu22/movietorrentsearchtv/data/metadata/WikipediaMetadataProvider.kt"
)
if "OkHttpClient()" in wikipedia_provider:
    fail("Wikipedia metadata provider must not construct a raw OkHttpClient (audit H5)")
for token in ("body.source()", "readUpTo(", "MAX_PAYLOAD_BYTES"):
    if token not in wikipedia_provider:
        fail(f"Wikipedia metadata provider must use a bounded payload read: {token}")

banner = ROOT / "app/src/main/res/drawable/tv_banner.png"
if not banner.is_file():
    fail("Android TV banner is missing")
elif png_dimensions(banner) != (320, 180):
    fail(f"Android TV banner must be 320x180, found {png_dimensions(banner)}")

if ERRORS:
    print("Project verification FAILED:", file=sys.stderr)
    for error in sorted(set(ERRORS)):
        print(f" - {error}", file=sys.stderr)
    sys.exit(1)

print(f"Project verification passed ({len(files)} source text files checked).")
