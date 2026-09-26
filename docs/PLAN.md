# Mini Networks – Umsetzungsplan

Stand: Prototyp v0.1 (Branch `claude/mini-networks-android-prototype-9l0v8n`).
Dieses Dokument ist die Vorlage für eine spätere Umsetzung per Ultracode-Workflow in einer eigenen Session.
Jedes Arbeitspaket in Abschnitt 6 ist so geschnitten, dass ein Agent es allein umsetzen und selbst prüfen kann.

---

## 1. Spielidee in einem Absatz

Mini Networks ist ein ruhiges Puzzle- und Aufbauspiel im Stil von Mini Metro und Mini Motorways.
Auf einer Stadtkarte tauchen nach und nach **Geräte** auf (PC, Telefon, Laptop, Konsole, Smartphone, Smart-TV, Tablet, Smartwatch …).
Sie fordern **Dienste** an (Mail, Telefonie, Gaming, Streaming …), die nur bestimmte **Server** liefern.
Der Spieler verlegt **Kabel** und setzt **Router**, damit die Pakete ankommen.
Dienste unterscheiden sich in **Bandbreite** und **Ping**.
Die Kabeltechnik entwickelt sich über **Epochen**: ISDN (1995), DSL, TV-Kabel, Glasfaser.
Bleiben zu viele Anfragen liegen, läuft beim Gerät ein Countdown ab. Ist er voll, ist das Spiel vorbei.
Ziel ist, so viele Pakete wie möglich zuzustellen.

## 2. Was der Prototyp schon kann

| Bereich | Stand |
|---|---|
| Plattform | Natives Android, Kotlin, **keine Engine**, eigene `View` + Canvas, Game-Loop über `Choreographer` |
| Spiellogik | `game/World.kt`: reines Kotlin ohne Android-Abhängigkeit, per JUnit getestet |
| Geräte | 8 Gerätetypen mit eigenen Icons und eigenem Dienste-Mix, Freischaltung nach Woche |
| Dienste | Mail, Telefonie, Gaming, Streaming mit Bandbreite (Paketgröße) und Ping-Limit |
| Kabel | ISDN, DSL, Kabel, Glasfaser mit Kapazität, Latenz pro Feld, Tempo und Preis pro Feld |
| Routing | Dijkstra nach Ping; nur Kabel mit genug Kapazität zählen; fremde Server leiten nicht weiter |
| Ports | Gerät 2, Server 4, Router 6: Router werden als Verteiler gebraucht |
| Wasser | Fluss auf der Karte; Kabel darüber kosten 2 Budget extra pro Wasserfeld |
| Wochen | Alle 45 s: +12 Budget, +1 Router, neue Technik, neue Geräte, neue Server |
| Stau | Kabel tragen begrenzte Bandbreite gleichzeitig; Pakete warten an Knoten |
| Game Over | ≥ 6 wartende Anfragen → roter Ring füllt sich in 18 s → „Netz überlastet“ |
| Grafik | Zwei Stile umschaltbar: **Flat** (Mini-Metro, 45°-Kabel) und **Isometrisch** (2,5D-Kacheln) |
| Steuerung | Ziehen = Kabel legen · Tippen auf Kabel = Upgrade auf gewählte Technik bzw. entfernen · Router-Knopf + Feld tippen |
| Tests | `WorldTest` (Regeln), `ScreenshotTest` (Robolectric rendert beide Stile nach `docs/screenshots/`) |

Die Stilstudie mit vier Looks (Flat, Iso, Pixel, Platine) liegt in `docs/style-explorations.html`.

### Dateien

```
app/src/main/java/com/mininetworks/game/
  MainActivity.kt           Vollbild-Activity, hostet GameView
  game/Model.kt             Service, Device, CableType, Node, Cable, Packet, Route, Geometry
  game/World.kt             Spielzustand, Regeln, Simulation, Routing
  render/Renderer.kt        Renderer-Interface, DeviceIcons, CableStyles, ServiceColors, Shapes
  render/FlatRenderer.kt    Stil A
  render/IsoRenderer.kt     Stil B
  ui/GameView.kt            Game-Loop, HUD, Touch-Eingabe
app/src/test/java/com/mininetworks/game/
  game/WorldTest.kt         Regeltests
  render/ScreenshotTest.kt  Rendert Szenen als PNG
```

### Bauen und Prüfen

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # Android SDK 35 nötig
./gradlew testDebugUnitTest      # Regeln + Screenshots nach docs/screenshots/
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
```

Der Screenshot-Test lädt beim ersten Lauf das Android-Framework für Robolectric (~350 MB) von Maven Central.
In abgeschotteten Containern die JARs `android-all-instrumented-14-robolectric-10818077-i7.jar` und
`android-all-instrumented-15-robolectric-12650502-i7.jar` vorab in einen Ordner laden und
`ROBOLECTRIC_DEPS_DIR=<ordner>` setzen.

## 3. Spieldesign (Soll)

### 3.1 Dienste

| Dienst | Form | Bandbreite | Ping-Limit | Richtung | Idee |
|---|---|---|---|---|---|
| Mail | ■ | 1 | – | ↑↓ | Anspruchslos, Einstieg |
| Telefonie | ◆ | 1 | 150 ms | ↔ Gerät–Gerät über Vermittlung | Erste Echtzeit |
| Gaming | ▲ | 1 | 60 ms | ↔ | Braucht kurze, schnelle Wege |
| Streaming | ● | 3 | – | ↓ | Braucht dicke Leitungen |
| Videocall (neu) | ⬟ | 2 | 120 ms | ↔ | Bandbreite **und** Ping |
| Kamera-Upload (neu) | ⬢ | 2 dauerhaft | – | ↑ | Belastet dauerhaft, statt in Wellen |
| Cloud-Backup (neu) | ✚ | 4 | – | ↑ nachts | Lastspitzen zu festen Zeiten |

Die Formen bleiben die Hauptinformation (farbenblind-tauglich), die Farbe unterstützt nur.

### 3.2 Geräte und Epochen

| Epoche (Woche) | Jahr | Neue Geräte | Neue Technik | Neue Server |
|---|---|---|---|---|
| 1 | 1995 | PC, Telefon | ISDN | Mail, Vermittlung |
| 2 | 1998 | Laptop | DSL | – |
| 3 | 2001 | Konsole | TV-Kabel | Game-Server |
| 4 | 2004 | Smartphone, Smart-TV | – | Streaming-CDN |
| 5 | 2007 | Tablet | Glasfaser | – |
| 6 | 2010 | Smartwatch | WLAN-Access-Point (neu) | zufällig |
| 7+ | 2013+ | Kamera, Smart-Home (neu) | Mobilfunkmast 4G/5G (neu) | zufällig |

Jahre sind spielerisch gerundet, nicht historisch exakt.

### 3.3 Kabel und Funk

| Technik | Kapazität | Latenz/Feld | Preis/Feld | Besonderheit |
|---|---|---|---|---|
| ISDN | 2 | 22 ms | 1 | trägt kein Streaming |
| DSL | 4 | 11 ms | 1 | Standard |
| TV-Kabel | 6 | 8 ms | 2 | (neu) geteiltes Medium: Kapazität sinkt, wenn viele Geräte dranhängen |
| Glasfaser | 12 | 2,5 ms | 3 | teuer, nötig für Gaming über Distanz |
| Seekabel (neu) | – | – | +2/Wasserfeld | automatisch über Wasser |
| WLAN-AP (neu) | 4 | 5 ms | Item | verbindet alle Geräte im Radius 1,5 ohne Kabel, max. 4 Geräte |
| Mobilfunkmast (neu) | 8 | 15 ms | Item | Radius 3, nur mobile Geräte (Smartphone, Tablet, Uhr) |
| Richtfunk (neu) | 6 | 4 ms | Item | Punkt-zu-Punkt über Wasser/Hindernisse |

**WLAN-Interferenz (neu):** Access Points, deren Funkradien sich überlappen, stören sich gegenseitig.
Jeder AP hat einen Kanal (2,4 GHz: 1 / 6 / 11). Überlappende APs auf demselben Kanal teilen sich die Kapazität;
jeder weitere AP im Überlappungsbereich senkt Kapazität und maximale Geräteanzahl pro AP (z. B. −30 % je Nachbar, mind. 1 Gerät).
Gegenmittel für den Spieler: Kanal wechseln (Antippen des AP), 5-GHz-Upgrade (mehr Kanäle, aber kleinerer Radius)
oder den AP per Kabel statt noch einem AP ergänzen. Der Renderer zeigt Funkradien als Kreise; Überlappung auf gleichem Kanal färbt sich rot.
So entsteht ein eigenes kleines Puzzle: WLAN ist billig und flexibel, aber „einfach noch einen Router hinstellen“ macht es schlechter.

### 3.4 Wochen-Upgrade statt Automatik (wie Mini Metro)

Am Ende jeder Woche pausiert das Spiel und bietet **2 von 4** Belohnungen zur Wahl an:
+Budget, +2 Router, 1 WLAN-AP, 1 Rechenzentrums-Upgrade (Server verarbeitet mehr), 1 Cache-Knoten (liefert Streaming lokal aus).
Der Prototyp vergibt die Belohnungen noch automatisch.

### 3.5 Weitere Mechaniken (priorisiert)

1. **Server-Durchsatz:** Server verarbeiten nur N Pakete pro Sekunde, sonst stauen sie sich dort.
2. **Antworten:** Pakete laufen hin **und zurück**; der Ping zählt beide Wege.
3. **Kamera/Zoom:** Die Karte wächst mit der Zeit (wie Mini Motorways); Pinch-Zoom und Pan.
4. **Störungen:** Ein Bagger kappt ein Kabel, ein Stromausfall legt einen Router für 10 s lahm. Selten, angekündigt.
5. **Cache/CDN-Knoten:** Liefert Streaming aus der Nähe und entlastet das Backbone.
6. **Karten:** Echte Städte mit Flüssen (Berlin/Spree, Hamburg/Elbe, Köln/Rhein, München/Isar), jeweils mit eigener Freischaltung.
7. **Modi:** Normal, Endlos (kein Game Over), Kreativ (freies Bauen), Tagesaufgabe mit festem Seed.

### 3.6 Nicht-Ziele fürs MVP

Kein Multiplayer, keine Online-Pflicht, kein Shop, keine Werbung im MVP. iOS erst, wenn das Spiel trägt.

## 4. Technische Architektur

### 4.1 Entscheidungen

- **Kotlin + Android Canvas, keine Engine.** Die Grafik ist 2D/2,5D-Vektor, dafür reicht Canvas bei 60 fps locker.
  Eine Engine (libGDX, Godot) lohnt erst bei Shadern, vielen Partikeln oder einem iOS-Port.
  Die Trennung Logik ↔ Renderer hält diesen Weg offen.
- **Logik als reines Kotlin-Modul.** `game/` hat keine Android-Imports. Im MVP wandert es in ein eigenes Gradle-Modul `:core` (JVM),
  damit Tests schnell laufen und ein späterer Port (Kotlin Multiplatform, libGDX) die Logik übernehmen kann.
- **Fester Simulationsschritt.** `World.update` wird mit festen 1/60 s aufgerufen (Akkumulator), damit Replays und Tests deterministisch sind.
- **Renderer-Interface.** Jeder Stil implementiert `Renderer` (Projektion hin und zurück, Kabelgeometrie, Zeichnen).
  Neue Stile (Pixel, Platine) sind reine Zusatzarbeit ohne Eingriff in die Logik.
- **SurfaceView mit eigenem Render-Thread** statt `View.invalidate()`, sobald Zoom/Pan und größere Karten kommen.
- **Speichern:** `kotlinx.serialization` → JSON in `filesDir`, automatisch beim Pausieren. Highscores per DataStore.
- **Audio:** `SoundPool` für kurze Klänge (Paket zugestellt = Ton nach Dienst), leise generative Musik später.
- **Haptik:** kurzes Feedback beim Einrasten eines Kabels.
- **Sprachen:** Deutsch und Englisch über `strings.xml`; die Logik liefert nur IDs, keine Texte.
- **Barrierefreiheit:** Formen tragen die Information, zusätzlich eine alternative Farbpalette für Farbenblindheit, skalierbare UI.

### 4.2 Bekannte Vereinfachungen im Prototyp (bewusst)

- Die Spiellogik rechnet Kabellänge, Kosten und Wasser immer mit 45°-Geometrie. Der Iso-Renderer zeichnet L-Wege.
  Soll: Geometrie pro Kabel im Modell speichern (`CableLayout`), Renderer lesen sie nur.
- HUD-Texte sind fest auf Deutsch im Code.
- Keine Kamera; die Karte passt immer komplett auf den Bildschirm.
- Die Wochen-Belohnungen kommen automatisch statt zur Auswahl.
- Pakete laufen nur zum Server, nicht zurück.
- Kein Speichern, keine Einstellungen, kein Menü.

## 5. Offene Entscheidungen für euch

1. **Grafikstil fürs MVP:** Flat (A), Isometrisch (B), Pixel (C) oder Platine (D)? Empfehlung: A oder D fürs MVP, B als späteres Upgrade.
2. **Name:** „Mini Networks“ ist nah an „Mini Metro“/„Mini Motorways“ (Dinosaur Polo Club). Vor der Veröffentlichung Markenlage prüfen und eigenen Namen erwägen.
3. **Geschäftsmodell:** Einmalkauf (wie das Vorbild) oder Free-to-play mit Kauf der Vollversion.
4. **Mindest-Android-Version:** aktuell `minSdk 26` (Android 8), deckt ~97 % ab.
5. **Epochen-Thema** (1995 → heute) als roter Faden: ja/nein?

## 6. Arbeitspakete für den Ultracode-Workflow

Regeln für alle Pakete:
- Jedes Paket hat einen klaren Dateibereich. Pakete derselben Welle berühren keine gemeinsamen Dateien außer den genannten Schnittstellen.
- **Fertig heißt:** `./gradlew testDebugUnitTest assembleDebug lintDebug` ist grün, neue Regeln haben Unit-Tests,
  sichtbare Änderungen haben ein aktualisiertes Bild in `docs/screenshots/` (per `ScreenshotTest`).
- Die Logik (`game/` bzw. später `:core`) bleibt frei von Android-Imports.

### Welle 0 – Fundament (nacheinander, ein Agent)

**P0.1 Modul-Schnitt und Loop**
- `game/` in Gradle-Modul `:core` (Kotlin/JVM) verschieben, `:app` hängt davon ab.
- Fester Zeitschritt mit Akkumulator in der GameView; `World.update(FIXED_DT)`.
- `SurfaceView` + Render-Thread statt `View`.
- Fertig: alle bestehenden Tests grün, App läuft wie vorher.

**P0.2 Kabel-Layout im Modell**
- `CableLayout` (Liste von Rasterpunkten) pro Kabel speichern; Kosten, Wasser und Länge daraus berechnen.
- Renderer lesen die Geometrie nur noch aus dem Modell. Iso und Flat nutzen dasselbe Layout (Flat zeichnet 45°, Iso projiziert).
- Fertig: Test, dass Kosten und Packet-Position in beiden Renderern übereinstimmen.

### Welle 1 – parallel (4 Agenten)

**P1.1 Wochen-Belohnungen (Logik + UI)** · Dateien: `core/.../Rewards.kt`, `app/.../ui/RewardDialog.kt`
- Spiel pausiert am Wochenende, 2 von 4 Belohnungen zur Wahl, deterministisch per Seed.

**P1.2 Hin- und Rückweg, Server-Durchsatz** · Dateien: `core/.../World.kt` (Abschnitt Simulation), `core/.../Packet*`
- Antwortpakete, Ping zählt beide Wege, Server mit Warteschlange und Durchsatz.

**P1.3 Kamera: Zoom und Pan, wachsende Karte** · Dateien: `app/.../ui/Camera.kt`, `Renderer`-Projektion
- Pinch-Zoom, Zwei-Finger-Pan, Karte wächst alle 2 Wochen um einen Ring. Touch-Ziele bleiben ≥ 48 dp.

**P1.4 Speichern, Menü, Einstellungen** · Dateien: `app/.../ui/menu/*`, `core/.../Save.kt`
- Hauptmenü, Pause-Menü, Autosave, Highscore je Karte, Einstellungen (Ton, Haptik, Farbpalette, Sprache).

### Welle 2 – parallel (4 Agenten)

**P2.1 Funk: WLAN-AP und Mobilfunkmast** · neue `NodeKind`s mit Radius, Routing über Funkkanten
  inkl. WLAN-Interferenz (Kanäle 1/6/11, Kapazitätsverlust bei Überlappung, 5-GHz-Upgrade) laut 3.3, mit Unit-Tests für die Kapazitätsformel
**P2.2 Neue Dienste: Videocall, Kamera-Upload, Cloud-Backup** · inkl. Icons, Farben, Tests
**P2.3 Störungen: Bagger und Stromausfall** · Ankündigung, Effekt, Reparatur-Mechanik
**P2.4 Grafikstil fürs MVP ausbauen** (nach Entscheidung aus 5.1) · Animationen beim Kabellegen, Paket-Zustellung, Game Over

### Welle 3 – parallel (3 Agenten)

**P3.1 Karten:** 3 Städte mit echten Flussläufen als Daten (JSON), Auswahlbildschirm
**P3.2 Audio und Haptik:** SoundPool, Töne je Dienst, Haptik-Feedback
**P3.3 Tutorial:** 5 geführte Schritte (Kabel legen, Router, Kabeltypen, Ping, Überlast)

### Welle 4 – Release-Vorbereitung (nacheinander)

- Balancing-Durchlauf mit Bot: automatischer Greedy-Spieler in `:core`-Tests, der misst, wie lange er überlebt.
- Performance: 60 fps bei 60 Knoten und 200 Paketen auf einem Mittelklasse-Gerät.
- Play-Store: Icon, Screenshots, Datenschutzseite, signiertes AAB, interner Test-Track.

## 7. Startprompt für die Ultracode-Session

> Setze `docs/PLAN.md` im Repo `robinrehbein/mini-networks` um, beginnend mit Welle 0 und danach Welle 1.
> Halte dich an die Paket-Grenzen und die Definition von „fertig“ in Abschnitt 6.
> Offene Entscheidungen aus Abschnitt 5: [hier eure Antworten eintragen].
> ultracode
