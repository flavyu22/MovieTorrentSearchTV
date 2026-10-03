# Raport de reparare profesională — MovieTorrentSearchTV 2.0.0

Data: 5 august 2026

## Rezultat

Proiectul a fost curățat și restructurat pentru o distribuție Android TV mai sigură și mai predictibilă. Arhiva sursă finală nu include chei API, `local.properties`, cache-uri Gradle, directoare IDE, rezultate de build sau jurnale locale.

## Remedieri critice

1. Cheia TMDB găsită în arhiva 1.9.2 a fost eliminată din proiect și din cache-urile incluse. Proprietarul trebuie să o revoce și să configureze o cheie nouă exclusiv local/CI.
2. Autentificarea nu mai este memorată permanent. Pornirea la rece cere autentificare, iar aplicația se blochează după două minute în fundal.
3. HTTP public este blocat. Varianta Direct acceptă HTTP numai pentru TorrServer din loopback/rețea privată, după validare centrală și reverificarea redirecturilor.
4. Permisiunea de instalare APK și updaterul propriu există numai în varianta Direct. Varianta Play nu le poate activa.
5. Configurația instabilă AGP alpha a fost înlocuită cu AGP 9.3.1, Gradle 9.5.0 și JDK 17.
6. Scriptul Gradle care genera manifestul de update a fost reparat și validează URL-ul, repository-ul, tagul, dimensiunea APK-ului și SHA-256.
7. Lockfile-urile generate înainte de introducerea flavor-urilor au fost eliminate, deoarece descriau configurații inexistente. SBOM-ul folosește catalogul curent, iar lock-urile trebuie regenerate numai după rezolvarea completă Play/Direct în CI.

## Funcții Android TV noi

- **Lista mea** persistentă, disponibilă din meniul principal.
- Buton D-pad pentru adăugare/eliminare din Lista mea în ecranul de detalii.
- Grilă adaptivă pentru rezoluții și densități TV diferite.
- Profil automat low-RAM: mai puține postere preîncărcate și imagini cache mai mici.
- Restaurarea filmului selectat după terminarea procesului Android.
- Restaurarea modului Filme/Seriale/Istoric/Lista mea și a focusului de navigare.
- Blocare de sesiune fără închiderea forțată a aplicației atunci când pornește playerul extern.

## Calitate și verificări

- Teste JVM pentru politica de blocare a sesiunii.
- Teste pentru HTTPS, endpointurile TorrServer și rețelele private.
- Teste instrumentate pentru permisiunile diferite ale flavor-urilor Play/Direct.
- Guardrail static pentru secrete, cache-uri, manifest, cleartext, traduceri, toolchain și banner TV.
- Arhivare deterministă cu excluderea automată a fișierelor locale și sensibile.
- SBOM CycloneDX determinist pentru dependențele declarate în catalogul de versiuni.

## Limitare de mediu

În mediul de lucru curent, distribuția Gradle și Android SDK 37 nu au putut fi descărcate prin rețeaua procesului de build. Au fost executate verificările statice, verificarea sintactică Kotlin și testele independente de Android disponibile local. Buildul Gradle complet trebuie rulat într-un mediu Android Studio/CI cu acces la depozitele Google, Maven Central și Gradle înainte de semnarea publică.

## Obligatoriu înainte de publicare

- Revocarea cheii TMDB vechi și emiterea uneia noi.
- Rularea tuturor taskurilor din `README.md` pe un checkout curat.
- Test pe minimum un Android TV ARM64 real și un emulator low-RAM.
- Semnare cu o cheie de release permanentă și verificare cu `apksigner`.
- Confirmarea legalității surselor și actualizarea documentelor operatorului/distribuitorului.
