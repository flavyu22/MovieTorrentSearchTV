# Reparare integrală — MovieTorrentSearchTV 2.0.0

Data: 7 august 2026

## Rezultat

Sursa a fost reauditată și remediată peste revizia profesională 2.0.0. Au fost păstrate funcțiile Android TV, flavor-urile Play/Direct, politica de securitate, TorrServer, căutarea multi-sursă, Lista mea, istoricul și restaurarea stării.

## Defecte reparate în această revizie

1. **Filtre TMDB incomplete la căutare text** — genul și ratingul puteau fi ignorate de endpointurile de search. Rezultatele TMDB sunt filtrate acum local în mod consecvent pentru filme și seriale, inclusiv anul.
2. **Paginare neverificată** — pagini invalide sau excesive puteau ajunge la API. Pagina este normalizată în intervalul 1–500 și butonul Next nu mai depășește limita.
3. **Popup-uri de filtre suprapuse** — meniul Rating putea rămâne deschis când era activat Genre sau meniul principal. Toate meniurile concurente sunt închise explicit.
4. **Colectare lifecycle** — evenimentele UI din Activity sunt colectate numai în starea STARTED, evitând acțiuni nedorite în background.
5. **Profil low-RAM inconsistent** — unele televizoare cu heap mic nu sunt marcate de producător ca `isLowRamDevice`. Acum este verificată și `memoryClass <= 128 MB`.
6. **Poster detalii prea costisitor pe TV-uri slabe** — decode target redus la 360×540 și crossfade dezactivat pe profilul low-RAM; profilul normal rămâne 520×780.
7. **Generator SBOM rigid** — scriptul poate primi acum o cale de output explicită, utilă în CI/audit fără a crea automat `build/` în sursă.

## Verificări executate

- `scripts/verify-project.py`: trecut.
- Compilare sintactică/parsing Kotlin: fără erori de sintaxă detectate; referințele Android nu pot fi rezolvate fără Android SDK.
- Test pur Kotlin pentru `SessionLockPolicy`: trecut.
- Scanare pentru secrete/configurații locale și directoare generate: trecut înainte de arhivare.
- Generare SBOM într-o cale externă: trecut.
- Arhivare deterministă cu `scripts/package-source.py`: obligatoriu înainte de livrare.

## Limitarea mediului

Build-ul Gradle Android complet nu poate fi executat în acest container: `services.gradle.org` nu poate fi rezolvat prin DNS și Android SDK 37 nu este instalat. Nu este livrat un APK pretins validat. Sursa trebuie compilată în Android Studio/CI cu acces la Google/Maven Central/Gradle și apoi testată pe un Android TV real.
