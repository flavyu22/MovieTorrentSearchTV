# Configurare TMDB și postere

## 1. Obține cheia

1. Intră în contul tău TMDB dintr-un browser pe calculator.
2. Deschide **Settings / API** și solicită acces pentru o aplicație Developer.
3. După aprobare, copiază valoarea **API Key (v3 auth)**. Nu copia `API Read Access Token`, deoarece această versiune a aplicației folosește parametrul `api_key`.

## 2. Configurează proiectul local

Deschide fișierul `local.properties` din rădăcina proiectului. Android Studio îl creează de regulă automat. Păstrează linia `sdk.dir` și adaugă:

```properties
TMDB_API_KEY=CHANGE_ME_TMDB_V3_API_KEY
```

Exemplu de structură:

```properties
sdk.dir=C\:\\Users\\Nume\\AppData\\Local\\Android\\Sdk
TMDB_API_KEY=CHANGE_ME_TMDB_V3_API_KEY
```

Pe macOS/Linux, `sdk.dir` va avea formatul specific sistemului. Nu adăuga spații în jurul semnului `=` și nu pune cheia între ghilimele.

## 3. Recompilează complet

După salvare:

1. **File → Sync Project with Gradle Files**
2. **Build → Clean Project**
3. **Build → Rebuild Project**
4. Dezinstalează APK-ul vechi de pe televizor și instalează APK-ul nou.

Cheia este copiată în `BuildConfig` în momentul compilării. Adăugarea ei după ce APK-ul a fost deja construit nu modifică APK-ul existent.

## 4. Verificare rapidă

Din terminalul Android Studio, în rădăcina proiectului:

```bash
./gradlew :app:assemblePlayDebug
```

APK-ul se găsește în `app/build/outputs/apk/play/debug/app-play-debug.apk`. În aplicație, mesajul „TMDB nu este configurat” trebuie să dispară, serialele trebuie să se încarce, iar posterele TMDB trebuie să folosească adrese de forma `https://image.tmdb.org/t/p/w500/...`.

## Siguranță

`local.properties` este exclus din Git. Totuși, cheia poate fi extrasă din APK, deoarece este o aplicație client. Folosește o cheie separată pentru această aplicație, monitorizeaz-o și rotește-o dacă APK-ul este distribuit public.

## Dacă apare în continuare „TMDB nu este configurat”

Mesajul înseamnă că APK-ul a fost compilat cu o valoare goală, nu că serverul TMDB a respins cheia. Verifică faptul că `local.properties` este în rădăcina proiectului, lângă `settings.gradle.kts`, și că ai înlocuit complet textul `CHANGE_ME...` cu cheia reală API v3.

În terminalul Android Studio pe Windows rulează:

```powershell
.\gradlew.bat --stop
.\gradlew.bat :app:tmdbConfigurationStatus --no-configuration-cache
.\gradlew.bat clean :app:assemblePlayDebug --no-configuration-cache --rerun-tasks
```

Prima comandă de verificare trebuie să afișeze `TMDB_API_KEY configured: true`. Nu trimite și nu publica cheia. După compilare, dezinstalează versiunea veche de pe televizor și instalează APK-ul nou.


## Notă importantă pentru versiunea 2.0.0

Cheia care a fost inclusă în arhiva 1.9.2 trebuie revocată. Nu o reutiliza în niciun APK nou. Pentru Google Play compilează flavor-ul `play`; flavor-ul `direct` este destinat distribuirii controlate și include updaterul verificat și suportul TorrServer HTTP doar în LAN privat.
