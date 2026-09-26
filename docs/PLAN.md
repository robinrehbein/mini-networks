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
| Plattform | Natives Android, Kotlin, **keine Engine**, `SurfaceView` + Canvas (Hardware-Canvas), eigener Game-Thread |
| Game-Loop | Fester Simulationsschritt 1/60 s mit Akkumulator (`FixedStep`, max. 5 Schritte pro Frame); Touch- und Zurück-Eingaben landen in einer Queue, die der Game-Thread zu Beginn jedes Durchlaufs abarbeitet; die Simulation läuft nur auf dem Spiel-Bildschirm (`Screen.PLAYING`); `onPause` stoppt den Thread, öffnet das Pause-Menü und speichert |
| Spiellogik | Gradle-Modul `:core` (Kotlin/JVM, keine Android-Abhängigkeit), per JUnit getestet; Test-/Debug-Hooks (`grant`, `jumpToWeek`, `advanceToNextWeek`) sind öffentlich, aber per Opt-in `@DebugApi` markiert. Die Logik liefert nur IDs, keine Texte: Enums ohne Anzeigenamen, Fehler als `ConnectError`/`CableUpgradeError`/`ServerUpgradeError`, Wochen-Neuheiten als `WeekNews` |
| Geräte | 8 Gerätetypen mit eigenen Icons und eigenem Dienste-Mix, Freischaltung nach Woche |
| Dienste | Mail, Telefonie, Gaming, Streaming mit Bandbreite (Paketgröße) und Ping-Limit für Hin- und Rückweg (Telefonie 300 ms, Gaming 110 ms: Gaming über Distanz braucht Glasfaser) |
| Kabel | ISDN, DSL, Kabel, Glasfaser mit Kapazität, Latenz pro Feld, Tempo und Preis pro Feld |
| Kabel-Layout | Jedes Kabel speichert sein `CableLayout` im Modell: L-förmiger Weg über Feldmitten wie eine Straße, keine Diagonalen. Die Zieh-Spur des Spielers wählt, ob erst waagerecht oder erst senkrecht; ohne klare Spur gewinnt die Variante mit weniger Wasserfeldern. Länge, Latenz, Kosten (gelaufene Felder × Preis + Wasserzuschlag), Wassererkennung und Paketbewegung kommen aus dem Layout |
| Routing | Dijkstra nach Ping; nur Kabel mit genug Kapazität zählen; fremde Server leiten nicht weiter. `Route.pingMs` ist der Ping hin und zurück (2 × einfacher Weg), `Route.oneWayMs` der einfache Weg |
| Hin- und Rückweg | Eine Anfrage läuft zum Server, verbraucht dort eine Durchsatz-Marke und wird zur Antwort (`Packet.isResponse`), die dieselbe Route zurückläuft. Erst wenn die Antwort beim Gerät ankommt, zählt das Paket als zugestellt. Antworten belegen Kabelkapazität wie Anfragen; pro Schritt fahren erst alle Pakete und kommen an, dann steigen wartende Pakete ein: Antworten vor Anfragen, und eine Anfrage bekommt nur Platz, den keine auf dasselbe Kabel wartende Antwort braucht (das gilt am Gerät wie an jedem Router). Ein Gerät schickt nur dann eine neue Anfrage los, wenn nach allen an beiden Enden wartenden Paketen noch Platz auf dem ersten Kabel ist. So können Anfragen mit Rückstau weder das direkte Kabel noch eine Router-Strecke zustopfen, und Antworten stauen sich nicht am Server (getestet auch mit mehreren Geräten hinter einem Router). Wird ein Kabel entfernt, geht auch eine unterwegs verlorene Antwort als Anfrage zurück in die Warteschlange. Antworten sind kleiner und nur umrandet gezeichnet |
| Ports | Gerät 2, Server 4, Rechenzentrum 8, Router 6, WLAN-AP 2, Mobilfunkmast 4: Router werden als Verteiler gebraucht (`Node.maxPorts`) |
| Funk | `RadioType` in `:core` (`Radio.kt`): **WLAN-Access-Point** (`NodeKind.ACCESS_POINT`, Radius 1,5, Kapazität 4, max. 4 Geräte, 5 ms, ab Woche 6/2010) und **Mobilfunkmast** (`NodeKind.CELL_TOWER`, Radius 3, Kapazität 8, ohne Geräte-Grenze, 15 ms, nur Smartphone/Tablet/Uhr (`Device.mobile`), ab Woche 7/2013). Funkknoten werden wie Router aus einem Vorrat gesetzt und selbst per Kabel angebunden; Funkkanten (`RadioLink`) zu den Geräten im Radius (Abstand der Feldmitten) entstehen automatisch, nächste zuerst (Gleichstand: Knoten-ID), bis die Geräte-Plätze voll sind. Kabel und Funk sind beide `Link`s: Routing, Kapazität und Paketbewegung laufen über dieselbe Logik; alle Funkkanten eines Knotens teilen sich seine Kapazität (`Link.medium`). Eine Funkkante trägt nur Verkehr des Geräts selbst und nur als erster Schritt seiner Route (kein Umweg über ein anderes Funk-Gerät, kein per Kabel angehängtes Gerät hinter einem Funk-Gerät, damit Mast-Beschränkung und Geräte-Grenze halten), ein Kabel zwischen Funkknoten und Gerät ersetzt die Funkkante. Funkkanten werden nicht gespeichert, sondern bei jeder Änderung (Knoten, Kabel, Kanal, Band) neu berechnet; Pakete auf weggefallenen Kanten gehen zurück in die Warteschlange |
| WLAN-Interferenz | Kanäle 1/6/11 (2,4 GHz); ein neuer AP startet auf Kanal 1, Antippen schaltet weiter. APs auf demselben Kanal, deren Kreise sich überlappen (Abstand < Summe der Radien), stören sich: je Nachbar −30 % Kapazität und Geräte-Plätze, gerundet, mind. 1 (`Wifi.reduced`: 4 → 3 → 2 → 1). Halten (≥ 0,5 s) schaltet einen AP für 6 Budget auf **5 GHz**, sobald die Zeit um ist (ein sich füllender Ring um den AP zeigt den Fortschritt, Haptik beim Umschalten; Wegziehen bricht ab): Kanäle 36/40/44/48, Radius 1 (`World.upgradeTo5Ghz`, Fehler als `WifiUpgradeError`). Masten haben keine Kanäle und stören sich nicht. Die Renderer zeigen Funkkreise (Iso: Ellipsen auf dem Boden) in der Kanalfarbe, die Überlappung gleicher Kanäle rot mit rotem Rand, Funkkanten als laufende gestrichelte Linien, am AP ein Kanal-Schild |
| Karte und Wachstum | Festes Raster 32 × 20 (`World.bounds`), aber nur der freigeschaltete Block `World.unlocked` ist im Spiel: Start 16 × 10 in der Mitte, alle 2 Wochen (Woche 3, 5, 7 …) ein Ring mehr, jede Seite hält am Rasterrand an (`World.unlockedArea(week)`, deterministisch aus der Woche; ab Woche 17 ist alles frei). Geräte und Server entstehen nur im Block (ein Feld Abstand zu seinem Rand), Router und Rechenzentrums-Felder nur auf freigeschalteten Feldern (`isFree`). Außerhalb wird gedimmt gezeichnet (Iso: ausgewaschene Kacheln, Flat: Schleier), der Block hat eine Umrandung. Wächst die Karte, zeigt das HUD nach der Belohnungswahl „Die Stadt wächst“ |
| Kamera | `render/Camera.kt` (reines Kotlin): Zoom und Pan über jeder Projektion. Jeder Stil bildet die Welt erst in seine „Map-Einheiten“ ab (`Renderer.toMap`/`fromMap`, Flat 1:1, Iso eine Einheit pro Kachelbreite), die Kamera macht daraus Pixel; jeder Stil hat eine eigene Kamera. Pinch-Zoom um den Fingermittelpunkt (`TwoFingerGesture`), Zwei-Finger-Pan, Ziehen auf leerem Boden verschiebt ebenfalls, Doppeltipp auf leeren Boden passt den Block wieder ein (weich animiert). Ein-Finger-Ziehen von einem Knoten legt weiter Kabel; ein zweiter Finger bricht das Kabel ab. Zoom-Grenzen: hinaus bis das ganze Raster sichtbar ist, hinein bis etwa 6 × 4 Felder; die Bildmitte bleibt über dem Raster. Der Startblick passt den Block zwischen die HUD-Zeilen; wächst die Karte, zoomt die Kamera mit, solange der Spieler sie nicht selbst bewegt hat |
| Touch-Ziele | Knoten und Kabel werden in Bildschirm-Pixeln getroffen (`Renderer.nodeAtScreen`/`cableAtScreen`, `TouchTargets`): Radius mindestens 24 dp, also ≥ 48 dp Ziel bei jedem Zoom |
| Wasser | Fluss auf der Karte; Kabel darüber kosten 2 Budget extra pro Wasserfeld (gezählt auf den Feldern des gespeicherten Layouts) |
| Wochen | Alle 45 s: neue Technik, neue Geräte, neue Server, neue Funktechnik (`WeekNews.radios`); die Freischalt-Meldung bleibt sichtbar |
| Wochen-Belohnungen | Beim Wochenwechsel pausiert die Simulation (`World.rewardOffer`), bis der Spieler eine von **2** Belohnungen wählt: +16 Budget, +2 Router oder Server-Gutschein (nächste Server-Stufe gratis; nur im Angebot, solange ein Server noch wachsen kann). Das Angebot ist deterministisch aus Seed und Woche (`Rewards.offer`), unabhängig vom Spielverlauf. Keine automatische Wochen-Gutschrift mehr. UI: `RewardDialog`, zwei große Karten mit isometrischem Mini-Diorama auf dem Canvas, Wahl per Tippen (Finger runter und hoch auf derselben Karte). Seit P2.1 auch **+1 WLAN-AP** (ab Woche 6) und **+1 Mobilfunkmast** (ab Woche 7) im Pool (`World.eligibleRewards`), neue Belohnungen hängen hinten an, damit frühere Angebote gleich bleiben; der Cache-Knoten fehlt noch |
| Stau | Kabel tragen begrenzte Bandbreite gleichzeitig; Pakete warten an Knoten |
| Server-Stufen | Tipp auf Server = Aufrüsten (zuerst mit Server-Gutschein, sonst mit Budget), höhere Türme, begrenzter Durchsatz; offene Gutscheine stehen im HUD. Stufe 4 **Rechenzentrum** (siehe 5.3): belegt 2×2 Felder, 8 Ports, 8 Anfragen/s, breites isometrisches Gebäude. Geht das Aufrüsten nicht, liefert `World.serverUpgradeError` einen Grund (`ServerUpgradeError`), den das HUD kurz als Text aus `strings.xml` anzeigt |
| Game Over | ≥ 6 wartende Anfragen → roter Ring füllt sich in 18 s → Karte „Netz überlastet“ mit zugestellten Paketen, Woche und Bestwert (bzw. „Neuer Bestwert!“), Knöpfe „Nochmal“ und „Hauptmenü“ |
| Menüs | Auf den Canvas gezeichnet im Look der Belohnungskarten (`ui/menu/MenuPanel`: helle Karte auf einer Platte, runde Pillen-Knöpfe, die beim Drücken einsinken; skaliert auf kurze Querformat-Bildschirme). **Hauptmenü** (Spielen, Fortsetzen, Einstellungen, Bestwert) links neben einer festen Demo-Stadt (`DemoCity`, Iso), **Pause-Menü** (Weiter, Einstellungen, Neu starten, Hauptmenü), **Einstellungen**, **Game Over**. Zurück-Taste: Spiel → Pause-Menü → Spiel, Einstellungen → vorheriger Bildschirm, Game Over → Hauptmenü, Hauptmenü → App beenden. Auswahl wie bei den Karten: Finger runter und hoch auf demselben Eintrag. Schalter-Beschriftungen werden bei Platzmangel erst bis 13 sp verkleinert, bevor sie gekürzt werden. Der „Pause“-Knopf bleibt auch während der Wochen-Belohnung tippbar (über dem Dialog gezeichnet); „Weiter“ kehrt zur offenen Auswahl zurück |
| Speichern | `Save` in `:core`: `World.snapshot()` → `WorldSnapshot` (kotlinx.serialization, JSON, mit Versionsnummer) und `World.restore`. Der Snapshot enthält den kompletten Zustand inkl. Paketen, Kabel-Layouts, offenem Belohnungsangebot und Zufallsgenerator (`ReplayableRandom` zählt die Ziehungen und spielt sie beim Laden nach), sodass ein geladenes Spiel exakt so weiterläuft wie das Original. `SaveStore` schreibt `savegame.json` in `filesDir` (erst in eine Temp-Datei, dann umbenennen) beim Öffnen des Pause-Menüs, beim Wechsel ins Hauptmenü und bei `onPause`; Game Over löscht den Stand. Beschädigte oder fremde Dateien werden ignoriert |
| Bestwert | `HighscoreStore` (SharedPreferences): bester Wert (zugestellte Pakete) je Szenerie; bisher nur die Standard-Szenerie `river_town` |
| Einstellungen | `SettingsStore` (SharedPreferences): **Ton** (schaltet vorerst nur den System-Klick der Knöpfe, Spielklänge kommen mit P3.2), **Haptik** (kurzer Tick beim Einrasten auf einen Zielknoten, Impuls beim Verlegen des Kabels), **Übersichtsmodus** (Flat statt Iso; ersetzt den früheren „Stil“-Knopf), **Farbenblind-Palette** (`ServiceColors.colorblind`, Okabe-Ito-Töne, getestet mit simulierter Prot- und Deuteranopie). Die Sprache folgt dem System |
| Grafik | **Isometrisch** (2,5D-Kacheln) ist der Standard, **Flat** (Mini-Metro-Look) ist der Übersichtsmodus in den Einstellungen; beide zeichnen Kabel und Pakete aus demselben Layout, Flat rundet die Ecken nur optisch ab. Die Zieh-Vorschau zeigt genau das Layout und den Preis, die beim Loslassen gebaut werden. Beide Stile zeichnen durch ihre Kamera (Zoom/Pan) |
| Texte | Alle UI-Texte in `strings.xml`: Deutsch als Standard (`values/`), Englisch in `values-en/`; die Sprache folgt dem System. Namen von Diensten, Geräten, Kabeln und Knoten sowie Fehler- und Neuheiten-Texte übersetzt `ui/Texts` aus den IDs der Logik; auch das Listen-Trennzeichen der Neuheiten kommt aus `strings.xml` (`list_separator`) |
| Steuerung | Ziehen von einem Knoten = Kabel legen (die Zieh-Spur bestimmt den Knick) · Tippen auf Kabel = Upgrade auf gewählte Technik bzw. entfernen · Router-Knopf + Feld tippen · „WLAN“/„Mast“-Knopf (zweite Reihe rechts, sobald erfunden) + Feld tippen · AP antippen = Kanal wechseln, halten = 5 GHz · Pinch = Zoom · zwei Finger oder Ziehen auf leerem Boden = Pan · Doppeltipp auf leeren Boden = Block einpassen · „Pause“ oder Zurück = Pause-Menü |
| Tests | `:core`: `WorldTest` (Regeln), `RoundTripTest` (Anfrage wird Antwort, Zustellung erst bei Rückkehr, gleiche Laufzeit zurück, Antworten teilen Kabelkapazität, wartende Antworten vor neuen Anfragen, Dauerdurchsatz bei Rückstau auf direktem ISDN-/DSL-Kabel und bei ausgelastetem Server, Ping = beide Wege, Gaming über Distanz nur mit Glasfaser), `DataCenterTest` (2×2-Belegung, Ausweich-Block, Fehler ohne Platz bzw. bei Wasser, Gutschein-Eignung, belegte Felder, 8 Ports, Durchsatz 5/s gegen 8/s), `RewardsTest` (Angebot deterministisch und eindeutig, Pause, Wirkung jeder Belohnung, Gutschein-Regeln, Freischalt-Meldung), `CableLayoutTest` (Layout-Form, Kosten = Layout, Wassererkennung, Knick-Wahl, Paketbewegung und Laufzeit), `FixedStepTest` (Zeitschritt, Determinismus), `MapGrowthTest` (Startblock, ein Ring alle 2 Wochen, Klemmen am Rand je Seite, Spawns nur im Block auch nach dem Wachsen, Router und Rechenzentrum nur auf freigeschalteten Feldern), `WirelessTest` (Kapazitätsformel, Werte laut Plan, Abdeckung im Radius inkl. Diagonale, die 4 nächsten Geräte, Mast nur für mobile Geräte, Kabel ersetzt Funkkante, Route und Ping über Funk und AP-Kabel, Mast langsamer als WLAN, AP muss selbst verkabelt sein, kein per Kabel angehängter PC über Smartphone zum Mast bzw. über Laptop zum AP, Anfrage und Antwort über Funk, geteilte AP-Kapazität, Interferenz senkt Kapazität und Plätze und Kanalwechsel hebt sie auf, Abstand 3 überlappt nicht, volle Kanäle sperren Streaming, verlorener Platz schickt Pakete zurück, 5 GHz, Masten ohne Kanal, Belohnungen ab Woche 6/7, Neuheiten, Platzieren aus dem Vorrat, Speichern und exakt gleiches Weiterlaufen, alte Stände ohne Funkfelder), `SaveTest` (JSON hin und zurück ergibt denselben Snapshot, ein geladenes Spiel läuft 2 Minuten exakt wie das Original weiter, Speichern ändert den Verlauf nicht, offenes Belohnungsangebot, Game Over mit ausgefallenem Knoten, eigenes Wasser, beschädigte/fremde Dateien → null, `ReplayableRandom` = `Random(seed)`); `:app`: `MenuFlowTest` (Start im Hauptmenü über der stehenden Demo-Stadt, Spielen/Pause/Weiter, Autosave bei Pause-Menü und `onPause`, „Fortsetzen“ nach Neustart der View stellt denselben Snapshot her, Hauptmenü aus der Pause behält das Spiel, Game Over speichert den Bestwert und löscht den Stand, Einstellungen wirken sofort und bleiben erhalten, Haptik folgt der Einstellung, Pause-Knopf während der Wochen-Belohnung, Neuheiten-Liste mit lokalem Trennzeichen, Zurück im Hauptmenü beendet, Deutsch als Standard/Fallback und Englisch per Systemsprache), `ServiceColorsTest` (Farbenblind-Palette bleibt bei simulierter Prot-/Deuteranopie unterscheidbar, die Standard-Palette nicht), `CameraTest` (JVM: Einpassen mit HUD-Rändern, Hin-/Rückrechnung bei jedem Zoom/Pan, Zoom um den Pivot, Grenzen, Animation, Zwei-Finger-Geste), `RendererCameraTest` (beide Stile: Welt → Bildschirm → Welt inkl. Kamera, Startblick passt den Block ein, Zoom-Bereich, Mitwachsen nur ohne Spieler-Eingriff, Touch-Ziele ≥ 48 dp bei jedem Zoom und jeder Dichte), `GameViewGestureTest` (Ein-Finger-Kabel, zweiter Finger bricht ab und zoomt, Pan auf leerem Boden, Doppeltipp, WLAN-Knopf setzt AP, Tippen wechselt den Kanal, Halten schaltet auf 5 GHz, schon während der Finger liegt, Wegziehen bricht das Halten ab), `RendererLayoutTest` (beide Stile liefern identische Kabelwege und Paketpositionen), `ScreenshotTest` (Robolectric rendert beide Stile, die Zieh-Vorschau und ein komplettes Spielbild mit HUD, die Wochen-Belohnung über der Iso-Karte, alle Belohnungskarten inkl. gedrückter Karte und WLAN-/Mast-Karte, Funk mit Interferenz, 5 GHz und Mast in beiden Stilen und mit HUD, dazu der Halte-Ring auf einem AP (`wireless-*.png`), Hin-/Rückweg mit Rechenzentrum in beiden Stilen (`round-trip-*.png`), die gewachsene Stadt eingepasst und ganz herausgezoomt (`map-grown-*.png`, `map-overview-*.png`), einen Pinch-Zoom (`camera-zoom-iso.png`), den Start einer neuen Partie im Iso-Stil (`new-game-iso.png`), Hauptmenü deutsch und englisch (`menu-main.png`, `menu-main-en.png`), Pause-Menü und Einstellungen (`menu-pause.png`, `menu-settings.png`), Game Over (`game-over.png`) und die Farbenblind-Palette (`palette-colorblind-iso.png`) nach `docs/screenshots/`; Texte deutsch, Menüs als Querformat-Handy 800 × 360 dp) |

Die Stilstudie mit vier Looks (Flat, Iso, Pixel, Platine) liegt in `docs/style-explorations.html`.

### Dateien

```
core/src/main/kotlin/com/mininetworks/game/game/   (Gradle-Modul :core, reines Kotlin/JVM)
  Model.kt                  Service, Device, CableType, Node (inkl. Footprint, Kanal), ServerUpgradeError, ConnectError, CableUpgradeError, WifiUpgradeError, WeekNews, Link, Cable, Packet (Anfrage/Antwort), Route, Geometry
  Radio.kt                  RadioType (WLAN-AP, Mobilfunkmast), Wifi (Kanäle, Interferenz-Formel), RadioLink
  CellRect.kt               Rechteckiger Feldblock (freigeschalteter Bereich, Wachsen um Ringe)
  CableLayout.kt            Cell, Bend, CableLayout (Kabelgeometrie auf dem Raster, Knick-Vorschlag aus der Zieh-Spur)
  World.kt                  Spielzustand, Regeln, Simulation, Routing
  Rewards.kt                Reward, RewardOffer, deterministische Auswahl der Wochen-Belohnungen
  FixedStep.kt              Fester Zeitschritt (1/60 s, Akkumulator, max. 5 Schritte pro Frame)
  Save.kt                   Save (JSON), WorldSnapshot und Teil-Snapshots
  ReplayableRandom.kt       Zufallsgenerator wie Random(seed), der sich speichern und wiederherstellen lässt
  DebugApi.kt               Opt-in-Markierung für Test-/Debug-Hooks
core/src/test/kotlin/com/mininetworks/game/game/
  WorldTest.kt              Regeltests
  RoundTripTest.kt          Hin- und Rückweg, Ping beider Wege
  DataCenterTest.kt         Server-Stufe 4 „Rechenzentrum“
  CableLayoutTest.kt        Kabel-Layout: Kosten, Wasser, Knick, Paketbewegung
  FixedStepTest.kt          Zeitschritt und Determinismus
  RewardsTest.kt            Wochen-Belohnungen
  MapGrowthTest.kt          Wachsende Karte
  SaveTest.kt               Speichern und Laden
  WirelessTest.kt           Funk: Abdeckung, Routing, Interferenz, 5 GHz, Belohnungen
app/src/main/java/com/mininetworks/game/
  MainActivity.kt           Vollbild-Activity, hostet GameView, startet/stoppt den Game-Thread, leitet Zurück weiter
  data/SaveStore.kt         Autosave-Datei in filesDir
  data/SettingsStore.kt     GameSettings in SharedPreferences
  data/HighscoreStore.kt    Bestwert je Szenerie in SharedPreferences
  render/Renderer.kt        Renderer-Interface (Projektion in Map-Einheiten, Picking in Bildschirm-Pixeln), TouchTargets, DeviceIcons, CableStyles, RadioStyles, ServiceColors, Shapes
  render/Camera.kt          Camera (Zoom, Pan, Einpassen, Animation), TwoFingerGesture, MapRect, ViewInsets
  render/FlatRenderer.kt    Stil A
  render/IsoRenderer.kt     Stil B
  ui/GameView.kt            SurfaceView, Game-Thread, Eingabe-Queue, HUD, Bildschirm-Wechsel (Menüs, Speichern, Einstellungen)
  ui/RewardDialog.kt        Wochen-Belohnung: zwei Karten im Iso-Look auf dem Canvas
  ui/Texts.kt               Anzeigetexte zu den IDs der Logik (aus strings.xml)
  ui/menu/MenuPanel.kt      MenuPage, MenuItem, MenuAction und das Zeichnen der Menükarten
  ui/menu/Screen.kt         Hauptmenü, Spiel, Pause, Einstellungen, Game Over
  ui/menu/DemoCity.kt       Feste Demo-Stadt hinter dem Hauptmenü
app/src/main/res/
  values/strings.xml        Deutsch (Standard)
  values-en/strings.xml     Englisch
app/src/test/java/com/mininetworks/game/
  render/ScreenshotTest.kt  Rendert Szenen, die Zieh-Vorschau und das Spielbild mit HUD als PNG
  render/RendererLayoutTest.kt  Beide Stile lesen Kabelweg und Paketposition aus dem Modell
  render/CameraTest.kt      Kamera-Mathematik auf der JVM
  render/RendererCameraTest.kt  Projektion inkl. Kamera, Einpassen, Mitwachsen, Touch-Ziele
  render/ServiceColorsTest.kt  Farbenblind-Palette
  ui/GameViewGestureTest.kt Kabel ziehen, Pinch, Pan, Doppeltipp
  ui/MenuFlowTest.kt        Menüs, Autosave, Fortsetzen, Bestwert, Einstellungen, Sprache
```

### Bauen und Prüfen

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # Android SDK 35 nötig
./gradlew testDebugUnitTest      # :core-Regeltests + Screenshots nach docs/screenshots/
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
Umgesetzt in P1.1 mit 2 von 3 (+Budget, +2 Router, Server-Gutschein als „Rechenzentrums-Upgrade“); P2.1 ergänzt +1 WLAN-AP (ab Woche 6) und +1 Mobilfunkmast (ab Woche 7). Der Cache-Knoten kommt in den Pool, sobald es ihn gibt.

### 3.5 Weitere Mechaniken (priorisiert)

1. **Server-Durchsatz:** umgesetzt inkl. Rechenzentrum als Stufe 4 (siehe 5.3).
2. **Antworten:** umgesetzt in P1.2: Pakete laufen hin **und zurück**; der Ping zählt beide Wege.
3. **Kamera/Zoom:** umgesetzt in P1.3: Die Karte wächst mit der Zeit (wie Mini Motorways); Pinch-Zoom und Pan.
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
- **SurfaceView mit eigenem Game-Thread** statt `View.invalidate()` (umgesetzt in P0.1).
- **Speichern:** `kotlinx.serialization` → JSON in `filesDir`, automatisch beim Pausieren. Highscores und Einstellungen per SharedPreferences (umgesetzt in P1.4; DataStore bräuchte AndroidX und Coroutines für zwei Handvoll Werte).
- **Audio:** `SoundPool` für kurze Klänge (Paket zugestellt = Ton nach Dienst), leise generative Musik später.
- **Haptik:** kurzes Feedback beim Einrasten eines Kabels.
- **Sprachen:** Deutsch und Englisch über `strings.xml`; die Logik liefert nur IDs, keine Texte.
- **Barrierefreiheit:** Formen tragen die Information, zusätzlich eine alternative Farbpalette für Farbenblindheit, skalierbare UI.

### 4.2 Bekannte Vereinfachungen im Prototyp (bewusst)

- Kein Sichtbarkeits-Culling: der Iso-Stil zeichnet immer alle 640 Kacheln (Performance-Check in Welle 4).
- Der Fluss läuft über das ganze Raster, auch durch gesperrte Felder; eigene Karten kommen mit den Szenerien (P3.1).
- Der Ton-Schalter wirkt bisher nur auf den System-Klick der Menü- und HUD-Knöpfe; eigene Klänge kommen mit P3.2.
- Die Demo-Stadt hinter dem Hauptmenü steht still (nur die LEDs blinken).

## 5. Entscheidungen

| # | Thema | Entscheidung |
|---|---|---|
| 1 | Grafikstil | **2,5D Isometrisch** (Stil B) ist der Hauptstil. Flat bleibt als optionaler „Übersichtsmodus“ in den Einstellungen, weil er bereits existiert und bei großen Karten lesbarer ist. |
| 2 | Name | Arbeitstitel „Mini Networks“ bleibt im Code; der Anzeigename kommt nur aus `strings.xml` (`app_name`), damit ein Umbenennen eine Zeile ist. Markenprüfung macht das Team. Alternativen: „Packet Town“, „Ping City“, „Netzstadt“, „Uplink“, „Hop“. |
| 3 | Geschäftsmodell | **Werbung + Einmalkauf „Werbefrei“ + Szenerien.** Details in 5.1. |
| 4 | Mindest-Android | **minSdk 26** (Android 8.0). Deckt praktisch alle aktiven Geräte ab, und die Werbe- und Billing-SDKs laufen damit. |
| 5 | Epochen | **Bleibt** als roter Faden innerhalb einer Partie (1995 → heute). Die Szenerien liefern zusätzlich Ort und Startepoche, siehe 5.2. |

### 5.1 Monetarisierung

- **Nie Werbung während des Spielens.** Interstitials nur zwischen Partien, höchstens jede dritte Partie und nicht in den ersten 3 Partien.
- **Rewarded Ads, freiwillig:** „Weiterspielen“ einmal pro Partie nach Game Over (Überlast-Ringe werden zurückgesetzt), oder +1 Router im Wochen-Menü.
- **Einmalkauf „Werbefrei“** entfernt Interstitials; Rewarded-Vorteile gibt es dann ohne Video.
- **Szenerien** schaltet man durch Spielen frei (Punkteziel in der vorherigen Szenerie) oder kauft sie einzeln bzw. als Paket.
- Technik: Google Mobile Ads SDK (AdMob) mit **UMP-Einwilligungsdialog (DSGVO, Pflicht in der EU)**, Google Play Billing Library.
  Alles hinter einem Interface `Monetization` im App-Modul; eine `NoOpMonetization` für Debug und Tests. Im Repo nur **Google-Test-IDs**; echte AdMob-IDs und Produkt-IDs trägt das Team später in `local.properties`/CI-Secrets ein.

### 5.2 Szenerien (Karten)

| Szenerie | Startjahr | Besonderheit | Freischaltung |
|---|---|---|---|
| Kleinstadt am Fluss | 1995 | Tutorial-Karte, ein Fluss | frei |
| Großstadt | 1998 | zwei Flüsse, dichte Innenstadt, Hochhäuser verdecken Funk | 1.500 Pakete in Kleinstadt oder Kauf |
| Insel & Hafen | 2004 | viel Wasser: Seekabel und Richtfunk wichtig | 3.000 Pakete in Großstadt oder Kauf |
| Bergdorf | 2001 | Berge blockieren Funk, Kabel über Pässe teurer | Kauf oder Paket |
| Zukunft 2030 | 2030 | Satelliten, 6G, Rechenzentren im Orbit | Kauf oder Paket |

### 5.3 Server-Stufen (umgesetzt im Prototyp)

Server haben Hardware-Stufen 1–4 mit 1,5 / 3 / 5 / 8 Anfragen pro Sekunde. Ein Tipp auf den Server rüstet für 8, 16 bzw. 28 Budget auf
(oder mit einem Server-Gutschein). Stufen 1–3 sind gestapelte Rack-Einheiten: große Server sind sichtbar höhere Türme.
Stufe 4 „Rechenzentrum“ belegt 2×2 Felder: einer der vier 2×2-Blöcke, die das Server-Feld enthalten (Reihenfolge: Server oben links,
oben rechts, unten links, unten rechts), dessen drei übrige Felder trocken, auf der Karte und frei von anderen Knoten sind.
Passt keiner, ist das Aufrüsten gesperrt (`ServerUpgradeError.NO_SPACE`; ein Gutschein wird dann auch nicht mehr angeboten).
Kabel docken weiter am ursprünglichen Server-Feld an und dürfen, wie bei allen Knoten, durch die belegten Felder laufen.
Das Rechenzentrum hat 8 Ports und wird als breite, flache Halle mit Rack-LEDs an beiden Wänden und Kühlung auf dem Dach gezeichnet.
Ist ein Server ausgelastet, stauen sich Anfragen am Kabelende und die LEDs leuchten rot.

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

**P0.2 Kabel-Layout im Modell (Iso-first)** · umgesetzt
- `CableLayout` (Liste von Rasterpunkten) pro Kabel speichern; Kosten, Wasser und Länge daraus berechnen.
- Kabel laufen wie Straßen als L über das Raster (keine Diagonalen); die Zieh-Spur wählt den Knick, sonst weniger Wasser.
- Renderer lesen die Geometrie nur noch aus dem Modell. Iso und Flat nutzen dasselbe Layout (Flat rundet die Ecken optisch, Iso projiziert).
- Fertig: Test, dass Kosten und Packet-Position in beiden Renderern übereinstimmen.
- Bekannt: Kabel dürfen sich Felder teilen und durch fremde Knoten-Felder laufen; durch die längeren L-Wege steigt der Ping
  diagonaler Verbindungen (Balancing in Welle 4).

### Welle 1 – parallel (4 Agenten)

**P1.1 Wochen-Belohnungen (Logik + UI)** · Dateien: `core/.../Rewards.kt`, `app/.../ui/RewardDialog.kt` · umgesetzt
- Spiel pausiert am Wochenende, 2 von 4 Belohnungen zur Wahl, deterministisch per Seed.
- Stand: Pool aus +Budget (16), +2 Router, Server-Gutschein. WLAN-AP (P2.1) und Cache-Knoten sind nicht im Pool, weil es die Items noch nicht gibt;
  P2.1 ergänzt `Reward` und `World.chooseReward`. Die feste Wochen-Gutschrift (+12 Budget, +1 Router) entfällt dafür (Balancing in Welle 4).

**P1.2 Hin- und Rückweg, Rechenzentrum-Stufe** · Dateien: `core/.../World.kt` (Abschnitt Simulation), `core/.../Model.kt` (`Packet`) · umgesetzt
- Antwortpakete, Ping zählt beide Wege; Server-Stufe 4 „Rechenzentrum“ (2×2 Felder).
- Stand: Ping-Limits auf Hin- und Rückweg umgestellt (Telefonie 150 → 300 ms, Gaming 60 → 110 ms, also etwas strenger als vorher);
  Antworten haben Vorrang beim Einfädeln, auch gegenüber neuen Anfragen des Geräts (Dispatch hält Platz für wartende Pakete frei). Balancing der Stufe-4-Kosten (28) in Welle 4.

**P1.3 Kamera: Zoom und Pan, wachsende Karte** · Dateien: `app/.../render/Camera.kt`, `Renderer`-Projektion, `core/.../CellRect.kt`, `World` (freigeschalteter Block) · umgesetzt
- Pinch-Zoom, Zwei-Finger-Pan, Karte wächst alle 2 Wochen um einen Ring. Touch-Ziele bleiben ≥ 48 dp.
- Stand: `Camera` liegt in `render/` statt `ui/`, weil beide Renderer sie besitzen (sonst Paket-Zyklus ui ↔ render).
  Zusätzlich verschiebt Ziehen auf leerem Boden die Karte. Die Wochenlogik und Balancing-Werte sind unverändert;
  mehr Fläche heißt aber längere Kabel und mehr Streuung der Geräte (Balancing in Welle 4).

**P1.4 Speichern, Menü, Einstellungen** · Dateien: `app/.../ui/menu/*`, `core/.../Save.kt` · umgesetzt
- Hauptmenü, Pause-Menü, Autosave, Highscore je Karte, Einstellungen (Ton, Haptik, Farbpalette, Sprache).
- Stand: Menüs auf dem Canvas im Iso-Karten-Look; Speicher-Klassen liegen in `app/.../data/` statt `ui/menu/`, weil sie keine UI sind.
  Alle Texte der Logik sind jetzt IDs (Enums ohne `label`, `connectError` liefert `ConnectError`, `lastEvent` wurde zu `lastNews: WeekNews`),
  Englisch liegt in `values-en/`. Highscores per SharedPreferences statt DataStore (keine neue Abhängigkeit).
  Neue Abhängigkeit: `kotlinx-serialization-json` in `:core`. Der Ton-Schalter hat bis P3.2 nur den Knopf-Klick.

### Welle 2 – parallel (4 Agenten)

**P2.1 Funk: WLAN-AP und Mobilfunkmast** · neue `NodeKind`s mit Radius, Routing über Funkkanten
  inkl. WLAN-Interferenz (Kanäle 1/6/11, Kapazitätsverlust bei Überlappung, 5-GHz-Upgrade) laut 3.3, mit Unit-Tests für die Kapazitätsformel · umgesetzt
- Stand: Kabel und Funkkanten teilen sich das Interface `Link`; eine Funkkante hat feste Latenz (5 bzw. 15 ms) statt Latenz pro Feld,
  die Kapazität gilt für alle Funkkanten eines Knotens zusammen. Festgelegt, wo 3.3 offen war: −30 % wird gerundet (4 → 3 → 2 → 1),
  „Überlappung“ heißt Abstand der Mittelpunkte kleiner als die Summe der Radien, der Mast hat keine Geräte-Grenze,
  WLAN versorgt alle Gerätetypen, 5 GHz kostet 6 Budget (Halten auf dem AP) und ist sofort verfügbar, Funkkanten tragen keinen Durchgangsverkehr (nur als erster Schritt ab dem Gerät selbst).
  Funkknoten gibt es nur als Wochen-Belohnung (kein Startvorrat); AP- und Mast-Knöpfe stehen in einer zweiten HUD-Reihe, weil die untere Reihe auf dem Handy voll ist.
  Richtfunk (3.3) ist nicht Teil von P2.1. Balancing (Preise, Latenzen, Belohnungs-Häufigkeit) in Welle 4.
**P2.2 Neue Dienste: Videocall, Kamera-Upload, Cloud-Backup** · inkl. Icons, Farben, Tests
**P2.3 Störungen: Bagger und Stromausfall** · Ankündigung, Effekt, Reparatur-Mechanik
**P2.4 Isometrischen Stil ausbauen** (Entscheidung 5, Nr. 1): Gebäude-Details, Schatten, Bäume/Deko · Animationen beim Kabellegen, Paket-Zustellung, Game Over

### Welle 3 – parallel (3 Agenten)

**P3.1 Szenerien:** die 5 Szenerien aus 5.2 als Daten (JSON), Auswahlbildschirm mit Freischaltung
**P3.4 Monetarisierung:** `Monetization`-Interface, AdMob + UMP + Play Billing laut 5.1, nur Test-IDs
**P3.2 Audio und Haptik:** SoundPool, Töne je Dienst, Haptik-Feedback
**P3.3 Tutorial:** 5 geführte Schritte (Kabel legen, Router, Kabeltypen, Ping, Überlast)

### Welle 4 – Release-Vorbereitung (nacheinander)

- Balancing-Durchlauf mit Bot: automatischer Greedy-Spieler in `:core`-Tests, der misst, wie lange er überlebt.
- Performance: 60 fps bei 60 Knoten und 200 Paketen auf einem Mittelklasse-Gerät.
- Play-Store: Icon, Screenshots, Datenschutzseite, signiertes AAB, interner Test-Track.

## 7. Startprompt für die Ultracode-Session

> Setze `docs/PLAN.md` im Repo `robinrehbein/mini-networks` um, beginnend mit Welle 0 und danach Welle 1.
> Halte dich an die Paket-Grenzen und die Definition von „fertig“ in Abschnitt 6.
> ultracode
