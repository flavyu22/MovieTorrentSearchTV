# Performance validation

The `:benchmark` module contains Android Macrobenchmark tests for startup and frame timing. Performance results are valid only when captured from a named Android TV device against a release-like build; generated Gradle logs are not treated as benchmark evidence.

## Run

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Record the device model, Android version, build commit, run count and percentile metrics in `results-template.md`. Keep raw Android Studio or CI test artifacts outside the source archive unless they are attached to a specific release.

## Baseline profile regeneration (required before each release)

The app ships a conservative hand-maintained seed profile at `app/src/main/baseline-prof.txt`
(human-readable ART baseline profile format: `HSPL<class>;-><method>` lines). The
`BaselineProfileGenerator` macrobenchmark in `:benchmark` produces the real, measured
profile. Regenerating it requires **physical Android TV hardware** — it cannot be done on
a CI VM or an emulator without a real image; treat CI results as smoke tests only.

Procedure on a workstation with one Android TV device/emulator connected:

```bash
# 1. Run the generator rule on the device (release-like variant is selected by the plugin).
./gradlew :benchmark:connectedBenchmarkAndroidTest

# 2. Pull the aggregated profile produced under the benchmark build output
#    (AndroidX drops it as baseline-prof.txt next to the connected test results):
#    benchmark/build/outputs/baseline-prof.txt
cp benchmark/build/outputs/baseline-prof.txt app/src/main/baseline-prof.txt

# 3. Re-verify the whole chain with the new profile baked in.
./gradlew :app:assembleDirectRelease :app:assemblePlayRelease
py -3 scripts/verify-project.py
```

Notes:

- Review the diff before committing: the generated file must stay text, must keep the
  `HSPL…` format, and should not explode in size (the seed is 5 lines; anything above a
  few hundred lines deserves a second look for over-collection).
- The file is already wired into packaging; `verify-project.py` only checks presence of
  the source tree, not profile content — content review is a human release step.
- Repeat the regeneration **immediately before each release** so the profile reflects the
  final startup path (the seed comment inside the file states the same requirement).

