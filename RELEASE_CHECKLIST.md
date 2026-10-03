# Production release checklist — 2.1.0

A production release is blocked until every applicable item is complete.

## Secrets and identity

- Revoke the TMDB key exposed in the original 1.9.2 archive.
- Configure the replacement key only through protected local/CI configuration.
- Confirm ownership of `io.github.flavyu22.movietorrentsearchtv`; keep it unchanged after first publication.
- Create, back up and protect the permanent Android release keystore.
- Never commit keystores, passwords, `local.properties`, populated `.env` files or API keys.

## Verification

- Run `python3 scripts/verify-project.py` from a clean checkout.
- Run unit tests for both `directDebug` and `playDebug`.
- Run lint for both variants with no errors.
- Assemble both debug and release variants.
- Assemble both macrobenchmark variants.
- Regenerate Gradle dependency locks for every Play/Direct configuration and review the diff.
- Generate and review Gradle dependency verification metadata.
- Generate the CycloneDX SBOM and, where available, enrich it with resolved transitive dependencies.
- Inspect APK signatures, manifests, permissions and supported ABIs with Android build tools.
- Confirm 16 KB page-size compatibility for every bundled native library, if native libraries are introduced.

## Device testing

- Test on a real ARM64 Android/Google TV device.
- Test on a low-RAM Android TV emulator/device.
- Play only: verify the loopback TorrServer exception works on-device (both `localhost` and `127.0.0.1` forms) since XML domain entries for IP literals are device-dependent.
- Verify D-pad focus through login, home, filters, My List, history, details, dialogs and back navigation.
- Verify cold-start lock and two-minute background lock.
- Verify process-death restoration from the details screen.
- Verify voice-search failure handling on devices without a recognizer.
- Verify external-player chooser behavior and cancellation.
- Direct only: verify private-LAN TorrServer over HTTP and reject a public HTTP endpoint.
- Play only: confirm no installer permission, no updater UI and no cleartext traffic capability.

## Publication

- Replace generic privacy/legal operator text with the distributor's exact identity and disclosures.
- Confirm source legality and compliance with all third-party service terms.
- Keep Play and Direct artifacts clearly named and distributed through their intended channels.
- Publish checksums and retain reproducible release records.
