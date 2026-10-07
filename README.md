# Mapy

Mapa z trasą po przystankach na zegarek z Wear OS (pisane pod Xiaomi Watch 2 Pro) i aplikacja towarzysząca na telefon z Androidem.

## Co robi

**Telefon** (`app`)
- układanie tras: wyszukiwanie miejsc, dodawanie przez przytrzymanie palca na mapie, przeciąganie przystanków na liście, usuwanie, odhaczanie
- wiele tras (np. jedna na każdy dzień wyjazdu); aktywna trasa jest pokazywana na zegarku
- trasa piesza po ścieżkach; odcinki dłuższe niż 3 km są traktowane jako przejazd i rysowane linią prostą
- pobieranie map wzdłuż trasy i wysyłanie ich na zegarek do użycia offline

**Zegarek** (`wear`)
- mapa z trasą, przystankami i bieżącą pozycją; nazwa następnego przystanku i odległość do niego
- śledzenie GPS w usłudze pierwszoplanowej, więc działa także przy wygaszonym ekranie
- wibracja przy zbliżaniu się do przystanku i automatyczne odhaczanie po dojściu
- dodawanie przystanku w miejscu, w którym się stoi, pomijanie i usuwanie przystanków
- tryb dokładny (3 s / 5 m) i oszczędny (10 s / 15 m)

Moduł `shared` zawiera wspólny model trasy i obliczenia geograficzne.

## Budowanie

```
gradlew :app:assembleDebug :wear:assembleDebug
```

Oba moduły mają ten sam `applicationId` i muszą być podpisane tym samym kluczem, inaczej synchronizacja telefon–zegarek nie zadziała.

## Dane

Mapy: © autorzy OpenStreetMap. Wyznaczanie tras: serwer OSRM prowadzony przez FOSSGIS. Wyszukiwanie: Nominatim. Publiczne serwery OSM są przeznaczone do lekkiego, osobistego użytku; adres serwera kafelków jest w `app/src/main/java/com/szymi/mapy/Net.kt`.
