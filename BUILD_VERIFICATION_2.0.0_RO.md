# Verificare tehnică — MovieTorrentSearchTV 2.0.0

Data: 7 august 2026

## Verificări executate în mediul de reparare

| Verificare | Rezultat |
|---|---|
| Guardrail sursă și securitate (`scripts/verify-project.py`) | Trecut după remedierea 2026-08-07 |
| Scanare secrete și fișiere locale | Trecut |
| Validare manifest Play/Direct și politici cleartext | Trecut |
| Completitudine traduceri pentru funcțiile noi | Trecut |
| Dimensiune banner Android TV 320×180 | Trecut |
| Verificare whitespace/patch (`git diff --check`) | Trecut |
| Compilare/test pur Kotlin pentru politica de blocare | Trecut |
| Generare SBOM pentru dependențele declarate | Trecut; output extern suportat |
| Gradle/Android SDK build complet | Neexecutabil în acest mediu |

## Motivul limitării

Procesul local nu poate rezolva DNS pentru `services.gradle.org`, iar Android SDK 37 nu este instalat. Wrapperul Gradle este fixat cu checksum, însă distribuția nu poate fi descărcată aici. Din acest motiv nu sunt livrate APK-uri pretins validate.

## Comenzi obligatorii într-un mediu Android complet

```bash
python3 scripts/verify-project.py
./gradlew \
  :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest \
  :app:lintDirectDebug :app:lintPlayDebug \
  :app:assembleDirectDebug :app:assemblePlayDebug \
  :app:assembleDirectRelease :app:assemblePlayRelease \
  :benchmark:assembleDirectBenchmark :benchmark:assemblePlayBenchmark \
  --stacktrace
```

Pentru publicare trebuie configurate o cheie TMDB nouă, semnarea release și variabilele update doar în secretele CI. Cheia găsită în arhiva 1.9.2 trebuie revocată.
