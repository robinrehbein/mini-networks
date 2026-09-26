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
| Geräte | 10 Gerätetypen mit eigenen Icons und eigenem Dienste-Mix, Freischaltung nach Woche; seit P2.2 **Überwachungskamera** (`Device.CAMERA`, nur Kamera-Upload) und **Smart-Home-Hub** (`Device.SMART_HOME`, Mail und Cloud-Backup), beide ab Woche 7 |
| Dienste | Mail, Telefonie, Gaming, Streaming, **Videocall** (⬟, Bandbreite 2, 240 ms), **Kamera-Upload** (⬢, 2) und **Cloud-Backup** (✚, 4) mit Bandbreite (Paketgröße) und Ping-Limit für Hin- und Rückweg (Telefonie 300 ms, Videocall 240 ms, Gaming 110 ms: Gaming über Distanz braucht Glasfaser). Jeder Dienst hat eine eigene Form (`Shape`: Quadrat, Raute, Dreieck, Kreis, Fünfeck, Sechseck, Plus). `Service.demand` legt fest, wann Geräte ihn anfordern: `RANDOM` (wie bisher, Auswahl nur unter diesen Diensten), `STREAM` (Kamera: eine Anfrage alle 2,5 s, in jeder Woche gleich, `Device.stream`), `NIGHTLY` (Cloud-Backup: nur um 02:00 Spielzeit, dann 2 Anfragen je Gerät, alle Geräte gleichzeitig; wartet noch ein Backup, startet kein neues). Uploads (`Service.upload`: Kamera-Upload, Cloud-Backup) bekommen als Antwort nur eine Quittung der Größe 1 (`Service.responseSize`) |
| Kabel | ISDN, DSL, Kabel, Glasfaser mit Kapazität, Latenz pro Feld, Tempo und Preis pro Feld |
| Kabel-Layout | Jedes Kabel speichert sein `CableLayout` im Modell: L-förmiger Weg über Feldmitten wie eine Straße, keine Diagonalen. Die Zieh-Spur des Spielers wählt, ob erst waagerecht oder erst senkrecht; ohne klare Spur gewinnt die Variante mit weniger Wasserfeldern. Länge, Latenz, Kosten (gelaufene Felder × Preis + Wasserzuschlag), Wassererkennung und Paketbewegung kommen aus dem Layout |
| Routing | Dijkstra nach Ping; nur Kabel mit genug Kapazität zählen; fremde Server leiten nicht weiter. `Route.pingMs` ist der Ping hin und zurück (2 × einfacher Weg), `Route.oneWayMs` der einfache Weg |
| Hin- und Rückweg | Eine Anfrage läuft zum Server, verbraucht dort eine Durchsatz-Marke und wird zur Antwort (`Packet.isResponse`), die dieselbe Route zurückläuft. Erst wenn die Antwort beim Gerät ankommt, zählt das Paket als zugestellt. Antworten belegen Kabelkapazität wie Anfragen; pro Schritt fahren erst alle Pakete und kommen an, dann steigen wartende Pakete ein: Antworten vor Anfragen, und eine Anfrage bekommt nur Platz, den keine auf dasselbe Kabel wartende Antwort braucht (das gilt am Gerät wie an jedem Router). Ein Gerät schickt nur dann eine neue Anfrage los, wenn nach allen an beiden Enden wartenden Paketen noch Platz auf dem ersten Kabel ist. So können Anfragen mit Rückstau weder das direkte Kabel noch eine Router-Strecke zustopfen, und Antworten stauen sich nicht am Server (getestet auch mit mehreren Geräten hinter einem Router). Wird ein Kabel entfernt, geht auch eine unterwegs verlorene Antwort als Anfrage zurück in die Warteschlange. Antworten sind kleiner und nur umrandet gezeichnet, Anfragen gefüllt mit hellem Rand (in beiden Stilen), damit sie auf dunklen DSL-/Koax-Kabeln sichtbar bleiben |
| Ports | Gerät 2, Server 4, Rechenzentrum 8, Router 6, WLAN-AP 2, Mobilfunkmast 4: Router werden als Verteiler gebraucht (`Node.maxPorts`) |
| Funk | `RadioType` in `:core` (`Radio.kt`): **WLAN-Access-Point** (`NodeKind.ACCESS_POINT`, Radius 1,5, Kapazität 4, max. 4 Geräte, 5 ms, ab Woche 6/2010) und **Mobilfunkmast** (`NodeKind.CELL_TOWER`, Radius 3, Kapazität 8, ohne Geräte-Grenze, 15 ms, nur Smartphone/Tablet/Uhr (`Device.mobile`), ab Woche 7/2013). Funkknoten werden wie Router aus einem Vorrat gesetzt und selbst per Kabel angebunden; Funkkanten (`RadioLink`) zu den Geräten im Radius (Abstand der Feldmitten) entstehen automatisch, nächste zuerst (Gleichstand: Knoten-ID), bis die Geräte-Plätze voll sind. Kabel und Funk sind beide `Link`s: Routing, Kapazität und Paketbewegung laufen über dieselbe Logik; alle Funkkanten eines Knotens teilen sich seine Kapazität (`Link.medium`). Eine Funkkante trägt nur Verkehr des Geräts selbst und nur als erster Schritt seiner Route (kein Umweg über ein anderes Funk-Gerät, kein per Kabel angehängtes Gerät hinter einem Funk-Gerät, damit Mast-Beschränkung und Geräte-Grenze halten), ein Kabel zwischen Funkknoten und Gerät ersetzt die Funkkante. Funkkanten werden nicht gespeichert, sondern bei jeder Änderung (Knoten, Kabel, Kanal, Band) neu berechnet; Pakete auf weggefallenen Kanten gehen zurück in die Warteschlange |
| WLAN-Interferenz | Kanäle 1/6/11 (2,4 GHz); ein neuer AP startet auf Kanal 1, Antippen schaltet weiter. APs auf demselben Kanal, deren Kreise sich überlappen (Abstand < Summe der Radien), stören sich: je Nachbar −30 % Kapazität und Geräte-Plätze, gerundet, mind. 1 (`Wifi.reduced`: 4 → 3 → 2 → 1). Halten (≥ 0,5 s) schaltet einen AP für 6 Budget auf **5 GHz**, sobald die Zeit um ist (ein sich füllender Ring um den AP zeigt den Fortschritt, Haptik beim Umschalten; Wegziehen bricht ab): Kanäle 36/40/44/48, Radius 1 (`World.upgradeTo5Ghz`, Fehler als `WifiUpgradeError`). Masten haben keine Kanäle und stören sich nicht. Die Renderer zeigen Funkkreise (Iso: Ellipsen auf dem Boden) in der Kanalfarbe, die Überlappung gleicher Kanäle rot mit rotem Rand, Funkkanten als laufende gestrichelte Linien, am AP ein Kanal-Schild |
| Szenerien | Seit P3.1: die 5 Szenerien aus 5.2 als Kotlin-Daten in `:core` (`Scenario.kt`: `Scenario`, `Scenarios`, `TerrainFeature`, `Terrain`, `ScenarioRule`, `Unlock`). Jede Szenerie legt Rastergröße, Startblock, Startwoche und -jahr, Startvorrat (Budget, Router, Funk) und ihr Gelände fest; das Gelände entsteht Schritt für Schritt aus Merkmalen (Fluss senkrecht/waagerecht, Meer, Insel mit zerklüfteter Küste, See/Hafenbecken, Berge, Hochhäuser; Lage und Radien als Anteil der Karte) mit dem Zufall der Welt, also deterministisch aus dem Seed. **Kleinstadt am Fluss** (32 × 20, 1995, ein Fluss, genau wie im Prototyp), **Großstadt** (36 × 22, Start 18 × 11, 1998, zwei Flüsse, Hochhaus-Innenstadt, Budget 30, 3 Router), **Insel & Hafen** (32 × 20, 2004, Meer mit Hauptinsel, drei Inselchen, Hafenbecken und Bucht, Budget 36, 1 Mobilfunkmast), **Bergdorf** (32 × 20, 2001, Bach und vier Bergmassive, Budget 34), **Zukunft 2030** (36 × 22, Start 20 × 12, Woche 8 = alles erfunden, Jahr 2030, Fluss, See, Hochhäuser, Budget 48, 3 Router, je 1 AP und Mast; Regeln `SIX_G`: Masten versorgen jedes Gerät, `ORBITAL_SERVERS`: alle Server starten auf Stufe 2). Eine spätere Startwoche bringt alles bis dahin Erfundene samt der fälligen Server mit (Mail und Telefonie in gegenüberliegenden Ecken, der Rest zufällig); das Jahr zählt ab dem Startjahr weiter (`World.year`), Tempo, Kartenwachstum und Störungen zählen die gespielten Wochen (`World.weeksPlayed`), damit eine späte Epoche nicht schon am Anfang schwerer ist. **Gelände** (`World.terrainAt`/`setTerrain`): Knoten nur auf Land (`isFree`); Kabel zahlen je Feld zusätzlich Wasser 2, Berg 3 („Pass“), Hochhaus 1 (`Terrain.cableExtra`, `World.terrainExtraOn`), der automatische Knick wählt die Variante mit weniger Aufschlag; Berge und Hochhäuser zwischen Funkknoten und Gerät blockieren die Funkkante (`World.inRadioSight`: Sichtlinie zwischen den Feldmitten, abgetastet alle 0,1 Felder, die Felder der beiden Knoten zählen nicht), Wasser nicht; Bagger graben nur auf Land. **Freischaltung** (`Scenarios.isUnlocked`): Kleinstadt frei, Großstadt ab 1.500 Paketen in einer Partie Kleinstadt, Insel ab 3.000 in Großstadt, Bergdorf und 2030 nur per Kauf; jede nicht freie Szenerie lässt sich auch kaufen. Der Kauf läuft über das Interface `monetization/Entitlements` (`ownsScenery`, `purchaseScenery`, Produkt-IDs `scenery_<id>` und `scenery_pack`), bis P3.4 mit `NoEntitlements` (besitzt nichts, verkauft nichts); `GameView.entitlements` nimmt die echte Umsetzung auf |
| Szenerie-Auswahl | „Spielen“ öffnet `ui/menu/SceneryPicker` (`Screen.SCENERIES`): fünf Karten im Menü-Look nebeneinander, oben „Zurück“ und der Titel. Jede Karte zeigt eine isometrische Vorschau der Startkarte (einmal je Größe mit dem Iso-Renderer in eine Bitmap gerendert, fester Vorschau-Seed), Name, „ab Jahr“, Besonderheit (bis 2 Zeilen) und unten den Bestwert bzw. „Noch nicht gespielt“. Gesperrte Karten sind entsättigt mit Vorhängeschloss und zeigen das Ziel mit Fortschritt („1.210 / 3.000 Pakete in Großstadt“ plus Balken) oder „Im Shop · einzeln oder im Paket“. Antippen einer offenen Karte startet eine neue Partie dort, einer gesperrten fragt den Shop und nennt sonst unten den Weg zur Freischaltung („Erreiche 3.000 Pakete in Großstadt oder kaufe die Szenerie“ bzw. „Käufe sind in dieser Version noch nicht möglich“). Zurück-Taste und „Zurück“ führen ins Hauptmenü. „Nochmal“ und „Neu starten“ bleiben in der Szenerie der Partie; das Pause-Menü nennt die Szenerie |
| Karte und Wachstum | Raster je Szenerie fest (`World.bounds`, Kleinstadt 32 × 20), aber nur der freigeschaltete Block `World.unlocked` ist im Spiel: Start in der Mitte (Kleinstadt 16 × 10), alle 2 gespielten Wochen (in der Kleinstadt Woche 3, 5, 7 …) ein Ring mehr, jede Seite hält am Rasterrand an (`World.unlockedArea(week)`, deterministisch aus der Woche; in der Kleinstadt ist ab Woche 17 alles frei). Geräte und Server entstehen nur im Block (ein Feld Abstand zu seinem Rand), Router und Rechenzentrums-Felder nur auf freigeschalteten Feldern (`isFree`). Außerhalb wird gedimmt gezeichnet (Iso: ausgewaschene Kacheln, Flat: Schleier), der Block hat eine Umrandung. Wächst die Karte, zeigt das HUD nach der Belohnungswahl „Die Stadt wächst“ |
| Kamera | `render/Camera.kt` (reines Kotlin): Zoom und Pan über jeder Projektion. Jeder Stil bildet die Welt erst in seine „Map-Einheiten“ ab (`Renderer.toMap`/`fromMap`, Flat 1:1, Iso eine Einheit pro Kachelbreite), die Kamera macht daraus Pixel; jeder Stil hat eine eigene Kamera. Pinch-Zoom um den Fingermittelpunkt (`TwoFingerGesture`), Zwei-Finger-Pan, Ziehen auf leerem Boden verschiebt ebenfalls, Doppeltipp auf leeren Boden passt den Block wieder ein (weich animiert). Ein-Finger-Ziehen von einem Knoten legt weiter Kabel; ein zweiter Finger bricht das Kabel ab. Zoom-Grenzen: hinaus bis das ganze Raster sichtbar ist, hinein bis etwa 6 × 4 Felder; die Bildmitte bleibt über dem Raster. Der Startblick passt den Block zwischen die HUD-Zeilen; wächst die Karte, zoomt die Kamera mit, solange der Spieler sie nicht selbst bewegt hat |
| Touch-Ziele | Knoten und Kabel werden in Bildschirm-Pixeln getroffen (`Renderer.nodeAtScreen`/`cableAtScreen`, `TouchTargets`): Radius mindestens 24 dp, also ≥ 48 dp Ziel bei jedem Zoom |
| Wasser | Flüsse, Meer und Seen je Szenerie; Kabel darüber kosten 2 Budget extra pro Wasserfeld (gezählt auf den Feldern des gespeicherten Layouts), Berge 3 und Hochhäuser 1 (siehe „Szenerien“). Iso: Berge als Felsgipfel mit Schneekappe ab 0,72 Höhe, Hochhäuser als Glastürme mit Fensterreihen (einige erleuchtet) auf Pflaster, beide mit Schatten in der gecachten Bodenebene und mit der Deko von hinten nach vorn sortiert; Flat: ein einzelner Fluss bleibt ein glattes Band, sonst abgerundete Wasserfelder, Berge als zweifarbige Dreiecke, Hochhäuser als graue Blöcke |
| Wochen | Alle 45 s: neue Technik, neue Geräte, neue Server, neue Funktechnik (`WeekNews.radios`); die Freischalt-Meldung bleibt sichtbar. Server-Fahrplan aus `Service.serverWeek`: Woche 3 Gaming, 4 Streaming, 6 Videocall, 7 Kamera-Upload, 8 Cloud-Backup, ab Woche 10 jede zweite Woche ein zufälliger Dienst |
| Tageszeit | Uhr in der Spielzeit (`World.hourOfDay`, `isNight`): ein Tag dauert 15 s (3 Tage pro Woche), Start 06:00, Nacht 22:00–06:00. Das HUD zeigt unter dem Wochenbalken „Tag · 14:20“ bzw. „Nacht · 23:40 · Backups um 02:00“, sobald es einen Cloud-Backup-Server gibt |
| Wochen-Belohnungen | Beim Wochenwechsel pausiert die Simulation (`World.rewardOffer`), bis der Spieler eine von **2** Belohnungen wählt: +16 Budget, +2 Router oder Server-Gutschein (nächste Server-Stufe gratis; nur im Angebot, solange ein Server noch wachsen kann). Das Angebot ist deterministisch aus Seed und Woche (`Rewards.offer`), unabhängig vom Spielverlauf. Keine automatische Wochen-Gutschrift mehr. UI: `RewardDialog`, zwei große Karten mit isometrischem Mini-Diorama auf dem Canvas, Wahl per Tippen (Finger runter und hoch auf derselben Karte). Seit P2.1 auch **+1 WLAN-AP** (ab Woche 6) und **+1 Mobilfunkmast** (ab Woche 7) im Pool (`World.eligibleRewards`), neue Belohnungen hängen hinten an, damit frühere Angebote gleich bleiben; der Cache-Knoten fehlt noch |
| Stau | Kabel tragen begrenzte Bandbreite gleichzeitig; Pakete warten an Knoten |
| Störungen | `Incidents.kt` in `:core`: **Bagger** (`IncidentKind.EXCAVATOR`) und **Stromausfall** (`POWER_OUTAGE`), beide 5 s vorher angekündigt. Nie in Woche 1–2, ab Woche 3 eine pro Woche, ab Woche 9 zwei, nie mehr (`Incidents.countIn`, selten: eine Bagger-Störung legt ein Kabel bis zu 25 s lahm). Wann und welche Art steht fest aus Seed und Woche (`Incidents.plan`, eigener Zufallsstrom, unabhängig vom Spielverlauf; die Woche wird in Abschnitte geteilt, sodass eine Störung zuschlägt, bevor die nächste angekündigt wird, und nie in den ersten 6 bzw. letzten 8 s). Das Ziel wird beim Ankündigen aus einem eigenen Zufallsstrom gezogen: der Bagger ein Kabel (gräbt auf einem trockenen Feld zwischen den Enden, das kein Knoten belegt), der Stromausfall einen verkabelten Router oder WLAN-AP; ohne passendes Ziel die andere Art, sonst nichts. Ein gekapptes Kabel (`World.isCut`) trägt nichts, bis der Spieler es für 3 Budget repariert (`World.repair`, Fehler als `RepairError`) oder es sich nach 20 s selbst repariert; ein Knoten ohne Strom (`World.isDark`) leitet 10 s nichts weiter, ein dunkler AP funkt nicht und stört nicht. Pakete auf, an oder vor gestörten Strecken gehen zurück in die Warteschlange (wie beim Entfernen eines Kabels). Entfernen des Kabels ruft den Bagger ab, erstattet aber nichts (`World.refundOf`), solange der Bagger angekündigt ist oder geschnitten hat; Abbauen und neu Verlegen kostet also das volle Kabel. Iso: kleiner Bagger aus Pfaden (Ketten, Kabine mit Fenster, Ausleger), der mit blinkender Rundumleuchte über dem Kabel wartet und nach dem Schnitt im Loch gräbt, pulsierender gelber Ring mit Countdown-Bogen am Boden, danach roter Bogen bis zur Selbstreparatur, rot gestricheltes Kabel; Router/AP flackern gelb und werden dann dunkel, mit Blitz-Plakette. Flat zeigt dasselbe als Seitenansicht. Das HUD listet jede Störung mittig unter dem Datum (gelb angekündigt, rot aktiv, mit Sekunden) |
| Server-Stufen | Tipp auf Server = Aufrüsten (zuerst mit Server-Gutschein, sonst mit Budget), höhere Türme, begrenzter Durchsatz; offene Gutscheine stehen im HUD. Stufe 4 **Rechenzentrum** (siehe 5.3): belegt 2×2 Felder, 8 Ports, 8 Anfragen/s, breites isometrisches Gebäude. Geht das Aufrüsten nicht, liefert `World.serverUpgradeError` einen Grund (`ServerUpgradeError`), den das HUD kurz als Text aus `strings.xml` anzeigt |
| Game Over | ≥ 6 wartende Anfragen → roter Ring füllt sich in 18 s → Karte „Netz überlastet“ mit zugestellten Paketen, Woche und Bestwert (bzw. „Neuer Bestwert!“), Knöpfe „Nochmal“ und „Hauptmenü“ |
| Menüs | Auf den Canvas gezeichnet im Look der Belohnungskarten (`ui/menu/MenuPanel`: helle Karte auf einer Platte, runde Pillen-Knöpfe, die beim Drücken einsinken; skaliert auf kurze Querformat-Bildschirme). **Hauptmenü** (Spielen → Szenerie-Auswahl, Fortsetzen, Einstellungen, Bestwert) links neben einer festen Demo-Stadt (`DemoCity`, Iso), **Pause-Menü** (Szenerie, Datum und Pakete; Weiter, Einstellungen, Neu starten, Hauptmenü), **Einstellungen**, **Game Over**. Zurück-Taste: Spiel → Pause-Menü → Spiel, Einstellungen → vorheriger Bildschirm, Game Over → Hauptmenü, Szenerie-Auswahl → Hauptmenü, Hauptmenü → App beenden. Auswahl wie bei den Karten: Finger runter und hoch auf demselben Eintrag. Schalter-Beschriftungen werden bei Platzmangel erst bis 13 sp verkleinert, bevor sie gekürzt werden. Der „Pause“-Knopf bleibt auch während der Wochen-Belohnung tippbar (über dem Dialog gezeichnet); „Weiter“ kehrt zur offenen Auswahl zurück |
| Speichern | `Save` in `:core`: `World.snapshot()` → `WorldSnapshot` (kotlinx.serialization, JSON, mit Versionsnummer) und `World.restore`. Der Snapshot enthält den kompletten Zustand inkl. Szenerie-ID (fehlt sie, ist es die Kleinstadt; eine unbekannte ID lädt nicht), Gelände (eine Zeile je Rasterreihe: `.` Land, `~` Wasser, `^` Berg, `#` Hochhaus), Paketen, Kabel-Layouts, offenem Belohnungsangebot, laufenden Störungen und Zufallsgenerator (`ReplayableRandom` zählt die Ziehungen und spielt sie beim Laden nach), sodass ein geladenes Spiel exakt so weiterläuft wie das Original. `SaveStore` schreibt `savegame.json` in `filesDir` (erst in eine Temp-Datei, dann umbenennen) beim Öffnen des Pause-Menüs, beim Wechsel ins Hauptmenü und bei `onPause`; Game Over löscht den Stand. Beschädigte oder fremde Dateien werden ignoriert |
| Bestwert | `HighscoreStore` (SharedPreferences): bester Wert (zugestellte Pakete) je Szenerie (`Scenario.id`), Game Over speichert und vergleicht in der Szenerie der Partie; `lastScenery` merkt sich die zuletzt gestartete Szenerie, deren Bestwert das Hauptmenü zeigt. Die Bestwerte schalten die nächsten Szenerien frei |
| Einstellungen | `SettingsStore` (SharedPreferences): **Ton** (Spielklänge und System-Klick der Knöpfe), **Haptik** (kurzer Tick beim Einrasten auf einen Zielknoten, Impuls beim Verlegen des Kabels), **Übersichtsmodus** (Flat statt Iso; ersetzt den früheren „Stil“-Knopf), **Farbenblind-Palette** (`ServiceColors.colorblind`, an Okabe-Ito angelehnte Töne, für 7 Dienste nachjustiert, getestet mit simulierter Prot- und Deuteranopie), Knopf **„Tutorial ansehen“** (seit P3.3). Die Sprache folgt dem System |
| Audio und Haptik | Seit P3.2: `audio/SoundPlayer` spielt über `SoundPool` (Nutzung „Game“, max. 8 Stimmen, lebt von `onResume` bis `onPause`) vier kleine WAV-Dateien aus `res/raw` (22,05 kHz, 16 Bit mono, zusammen ~106 KB): weiches **Zupfen** bei jeder Zustellung, je Dienst ein eigener Ton der C-Dur-Pentatonik über die Abspielrate (`ServicePitch`: Backup G4, Mail C5, Telefonie D5, Gaming E5, Streaming G5, Videocall A5, Kamera C6; gleicher Dienst höchstens alle 60 ms, verschiedene Dienste zugleich ergeben einen Akkord), **Klick** beim Verlegen und Reparieren eines Kabels, leiser fallender **Warnton**, wenn ein Gerät zu überlasten beginnt (einmal je Überlast, erneut erst, wenn sein Ring wieder leer war), **Glockenspiel** zur neuen Woche. Welche Ereignisse klingen, liest `SoundCues` in `:core` rein aus dem Zustand der Welt (nur im Spiel, eine neue oder geladene Partie wird still übernommen). Die Klänge erzeugt `SoundSynth` (Unit-Test-Quelle, additive Synthese mit `StrictMath` und festem Zufalls-Seed, daher bitgleich); `SoundAssetsTest` prüft, dass die eingecheckten Dateien genau dem Synth entsprechen und unter 300 KB bleiben (`REGENERATE_SOUNDS=1` schreibt sie neu). Ton und Haptik folgen den Einstellungen. Haptik: Tick beim Einrasten auf einen Zielknoten, Impuls beim Verlegen, Reparieren, Umschalten auf 5 GHz |
| Tutorial | Seit P3.3: `Tutorial` in `:core` (reine Zustandsmaschine, `TutorialStep`, `TutorialFocus`) führt in 5 Schritten durch die Kleinstadt am Fluss (fester Seed 7, Budget 80): **1 Kabel legen** (PC zum Mail-Server ziehen), **2 Router setzen** (Router-Knopf, dann Feld bei den zwei Telefonen; fertig, sobald ein Router zwei Kabel hat), **3 Kabeltypen** (Sprung auf 1998, DSL wählen und ein ISDN-Kabel aufrüsten), **4 Ping** (Sprung auf 2007, derselbe PC will über den Fluss zum Game-Server spielen; DSL und TV-Kabel sind zu langsam, erst Glasfaser schafft 110 ms), **5 Überlast** (ein neuer PC ohne Kabel mit 6 wartenden Mails, sein roter Ring füllt sich, bis er angeschlossen ist). Jeder Schritt baut beim Start seine Szene auf (`World.addClient`/`addServer`, `World.advanceEra`) und endet, wenn sein Ziel im Zustand der Welt erreicht ist (`Tutorial.update` nach jedem Simulationsschritt). Das Tutorial spielt in einer **geführten Welt** (`World(guided = true)`): Kalender steht (Wochenbalken leer), `advanceEra` springt in eine spätere Epoche und meldet die Neuheiten ohne Server, Belohnung oder Kartenwachstum, keine Spawns, keine Störungen, der Überlast-Ring stoppt bei 95 % (`GUIDED_MAX_OVERLOAD`), also kein Game Over. UI: `ui/TutorialOverlay` zeichnet links eine Karte im Menü-Look (Schritt x/5 mit Punkten, Titel, Text je Schritt und Teilschritt, „Überspringen“; am Ende „Spielen“ und „Hauptmenü“), die Karte wird rechts daneben eingepasst, dazu eine pulsierende Hervorhebung: Ringe um Knoten, gestrichelter Weg mit wanderndem Finger für ein zu ziehendes Kabel, Leuchten entlang von Kabeln, Rahmen um den Router- bzw. Kabel-Knopf. Tipps auf die Tutorial-Karte erreichen nie das Spielfeld. Beim **ersten Start** (weder gesehen noch ein Spielstand) öffnet die App direkt das Tutorial; Überspringen, Beenden oder „Hauptmenü“ merken sich das (`SettingsStore.tutorialSeen`). **Einstellungen → „Tutorial ansehen“** startet es erneut (eine laufende Partie wird vorher gespeichert und bleibt fortsetzbar). Das Tutorial wird nie gespeichert und zählt keinen Bestwert; „Neu starten“ im Pause-Menü startet es neu, das Pause-Menü zeigt „Tutorial · Schritt x/5“. Texte deutsch und englisch |
| Grafik | **Isometrisch** (2,5D-Kacheln) ist der Standard, **Flat** (Mini-Metro-Look) ist der Übersichtsmodus in den Einstellungen; beide zeichnen Kabel und Pakete aus demselben Layout, Flat rundet die Ecken nur optisch ab. Die Zieh-Vorschau zeigt genau das Layout und den Preis, die beim Loslassen gebaut werden. Beide Stile zeichnen durch ihre Kamera (Zoom/Pan) |
| Iso-Feinschliff | Seit P2.4: weiche Schlagschatten aller Gebäude (Grundriss vom Licht oben links weggezogen, je nach Höhe länger, in drei immer schwächeren Lagen statt Blur-Filter, damit es auch auf dem Hardware-Canvas gleich aussieht), leicht variierte Kacheln mit Grasbüscheln und ein paar Blumen, Deko auf freiem Land (`render/Scenery.kt`: Laubbäume, Tannen, Büsche und selten Häuschen, deterministisch aus Seed und Feld, Wald in Flecken; nie auf Wasser, verschwindet unter einem neuen Knoten oder Kabel und solange ein Bagger darauf steht (der Bagger meidet Deko-Felder, wo er kann); außerhalb des Blocks ausgewaschen), Glitzer-Streifen, die den Fluss hinabtreiben. Die statische Bodenebene (Hintergrund, Kacheln, Brett, Umrandung, Schatten, Deko) liegt in einer Bitmap in Bildschirmgröße und wird nur neu gezeichnet, wenn sich Karte (Wasser, Block, Knoten mit Stufe, Kabel) oder Kamera ändern; bewegt sich die Kamera, wird direkt gezeichnet, bis sie einen Frame lang stillsteht, damit Pan und Pinch nicht jedes Frame eine Bitmap hochladen. Animationen: ein neues Kabel wächst entlang seines Layouts (0,15–0,6 s, Funke an der Spitze; `Cable.builtAt`), wo eine Anfrage am Server ankommt, läuft ein Ring in der Dienstfarbe über den Boden und das Server-Schild hüpft, eine zugestellte Antwort steigt als kleine Form über dem Gerät auf (`World.arrivals`: Ankünfte der letzten Sekunde, nicht gespeichert, ohne Einfluss auf die Simulation). Game Over: die Kamera gleitet sanft (`Camera.glideTo`, `Renderer.focusOn`) auf das ausgefallene Gerät und zoomt 1,6-fach heran, rote Wellen laufen um es herum; nach 1,6 s (oder beim nächsten Tippen, Zurück, `onPause`; das Loslassen eines Fingers, der schon vor dem Game Over lag, z. B. beim Kabelziehen, überspringt nicht) folgt die Game-Over-Karte. Flat bleibt schlicht (nur der Kamera-Fokus) |
| Texte | Alle UI-Texte in `strings.xml`: Deutsch als Standard (`values/`), Englisch in `values-en/`; die Sprache folgt dem System. Namen von Diensten, Geräten, Kabeln und Knoten sowie Fehler- und Neuheiten-Texte übersetzt `ui/Texts` aus den IDs der Logik; auch das Listen-Trennzeichen der Neuheiten kommt aus `strings.xml` (`list_separator`) |
| Steuerung | Ziehen von einem Knoten = Kabel legen (die Zieh-Spur bestimmt den Knick) · Tippen auf Kabel = Upgrade auf gewählte Technik bzw. entfernen, ein gekapptes Kabel wird stattdessen repariert · Router-Knopf + Feld tippen · „WLAN“/„Mast“-Knopf (zweite Reihe rechts, sobald erfunden) + Feld tippen · AP antippen = Kanal wechseln, halten = 5 GHz · Pinch = Zoom · zwei Finger oder Ziehen auf leerem Boden = Pan · Doppeltipp auf leeren Boden = Block einpassen · „Pause“ oder Zurück = Pause-Menü |
| Tests | `:core`: `WorldTest` (Regeln), `RoundTripTest` (Anfrage wird Antwort, Zustellung erst bei Rückkehr, gleiche Laufzeit zurück, Antworten teilen Kabelkapazität, wartende Antworten vor neuen Anfragen, Dauerdurchsatz bei Rückstau auf direktem ISDN-/DSL-Kabel und bei ausgelastetem Server, Ping = beide Wege, Gaming über Distanz nur mit Glasfaser), `DataCenterTest` (2×2-Belegung, Ausweich-Block, Fehler ohne Platz bzw. bei Wasser, Gutschein-Eignung, belegte Felder, 8 Ports, Durchsatz 5/s gegen 8/s), `RewardsTest` (Angebot deterministisch und eindeutig, Pause, Wirkung jeder Belohnung, Gutschein-Regeln, Freischalt-Meldung), `CableLayoutTest` (Layout-Form, Kosten = Layout, Wassererkennung, Knick-Wahl, Paketbewegung und Laufzeit), `FixedStepTest` (Zeitschritt, Determinismus), `EffectCuesTest` (Kabel merkt sich den Bau-Zeitpunkt, geladene Kabel gelten als lange verlegt, Ankünfte am Server und beim Gerät mit Dienst und Zeit, nach einer Sekunde weg, nicht gespeichert), `SoundCuesTest` (eine Zustellung klingt einmal mit ihrem Dienst, der Server-Treffer nicht, gleichzeitige Zustellungen je Dienst einmal und in fester Reihenfolge, Überlast warnt einmal und erst nach leerem Ring wieder, Wochenwechsel einmal, eine andere Welt wird still übernommen), `TutorialTest` (geführte Welt ohne Spawns, Wochenwechsel, Störungen und Game Over, feste Karte, jeder Schritt wartet auf sein Ziel und geht genau einen weiter, Router braucht zwei Kabel, Epochen-Sprung 1998/2007 ohne Server, Belohnung und Wachstum, DSL und Kabel zu langsam für Gaming, erst Glasfaser, Überlast-Ring bleibt unter voll, Hervorhebung je Schritt, Überspringen), `MapGrowthTest` (Startblock, ein Ring alle 2 Wochen, Klemmen am Rand je Seite, Spawns nur im Block auch nach dem Wachsen, Router und Rechenzentrum nur auf freigeschalteten Feldern), `NewServicesTest` (Werte laut Plan, eigene Form je Dienst, Kamera und Smart-Home ab Woche 7, Server-Fahrplan Woche 6/7/8, keiner in Woche 9, zufällig ab 10, Videocall nur mit kurzem Ping bzw. Glasfaser, ein Videocall füllt ISDN, Kamera-Takt fest und unabhängig von der Woche, Kamera ohne Upload-Server still, Uploads bekommen Quittungen der Größe 1, Uhr, Backup-Welle um 02:00 bei allen Backup-Geräten gleichzeitig und keine neue, solange eine wartet, normale Anfragen wählen nie Backup, Backup braucht mehr als ISDN und wird zugestellt, ohne Server keine Backups, Speichern mitten im Takt läuft exakt weiter), `WirelessTest` (Kapazitätsformel, Werte laut Plan, Abdeckung im Radius inkl. Diagonale, die 4 nächsten Geräte, Mast nur für mobile Geräte, Kabel ersetzt Funkkante, Route und Ping über Funk und AP-Kabel, Mast langsamer als WLAN, AP muss selbst verkabelt sein, kein per Kabel angehängter PC über Smartphone zum Mast bzw. über Laptop zum AP, Anfrage und Antwort über Funk, geteilte AP-Kapazität, Interferenz senkt Kapazität und Plätze und Kanalwechsel hebt sie auf, Abstand 3 überlappt nicht, volle Kanäle sperren Streaming, verlorener Platz schickt Pakete zurück, 5 GHz, Masten ohne Kanal, Belohnungen ab Woche 6/7, Neuheiten, Platzieren aus dem Vorrat, Speichern und exakt gleiches Weiterlaufen, alte Stände ohne Funkfelder), `IncidentsTest` (keine Störung in Woche 1–2, danach steigende Anzahl bis 2 (selten), Plan deterministisch aus Seed und Woche und beide Arten kommen vor, Ankündigung genau zur geplanten Zeit, nie überlappend und vor Wochenende zugeschlagen, Bagger kappt erst nach 5 s und Pakete gehen zurück, Selbstreparatur nach 20 s, Reparatur per Budget erst nach dem Schnitt, zu wenig Budget, Entfernen ruft den Bagger ab, ohne Erstattung unter angekündigtem Bagger oder gekapptem Kabel (Abbauen und neu Verlegen kostet), unberührtes Kabel voll erstattet, Umweg über ein zweites Kabel, Router 10 s dunkel und ohne Pakete, dunkler AP ohne Funkkanten und ohne Interferenz, unverkabelte Router bleiben verschont, ohne Ziel keine Störung, spätere Wochen bringen mehr, gleicher Seed gleiche Störungen, Speichern während Ankündigung und Wirkung läuft exakt weiter, alte Stände ohne Störungsfelder, Debug-Hooks), `ScenarioTest` (fünf Szenerien laut 5.2 mit Startjahr und Freischaltung, Startwochen passen zu den Epochen und 2030 hat alles, Freischaltung per Bestwert der vorherigen Szenerie oder Kauf, Kleinstadt hat genau den Prototyp-Fluss, jede Szenerie hat ihr Gelände (zwei Flüsse und Hochhäuser, Meer an allen Rändern, Berge auch im Startblock), Gelände deterministisch aus dem Seed, jede Szenerie startet spielbar für 20 Seeds (genug Land, Knoten nur auf Land im Block, Server aller fälligen Dienste, Geräte), spätere Szenerie startet in Woche/Jahr ihrer Epoche und zählt weiter, Wachstum und Störungen nach gespielten Wochen, Berge und Hochhäuser ohne Knoten und mit Kabel-Aufschlag, Knick meidet den Pass, Berge und Hochhäuser blockieren Funk, Wasser nicht, Gerät im Funkschatten ohne Route, 6G versorgt einen PC, Orbit-Server auf Stufe 2, Bagger nur auf Land, Speichern und exakt gleiches Weiterlaufen in jeder Szenerie, alte Stände sind die Kleinstadt), `SaveTest` (JSON hin und zurück ergibt denselben Snapshot, ein geladenes Spiel läuft 2 Minuten exakt wie das Original weiter, Speichern ändert den Verlauf nicht, offenes Belohnungsangebot, Game Over mit ausgefallenem Knoten, eigenes Wasser, beschädigte/fremde Dateien → null, `ReplayableRandom` = `Random(seed)`); `:app`: `TutorialFlowTest` (erster Start öffnet das Tutorial, Überspringen führt ins Hauptmenü und wird gemerkt, ein Spielstand zählt als nicht erster Start, Tutorial wird nie gespeichert, erneut aus den Einstellungen ohne Verlust der laufenden Partie, „Neu starten“ startet das Tutorial neu, Schritte 1–3 per Touch, Tipps auf die Sprechblase erreichen die Karte nie, „Spielen“ am Ende startet eine neue Kleinstadt-Partie), `MenuFlowTest` (Start im Hauptmenü über der stehenden Demo-Stadt, Spielen/Pause/Weiter, Autosave bei Pause-Menü und `onPause`, „Fortsetzen“ nach Neustart der View stellt denselben Snapshot her, Hauptmenü aus der Pause behält das Spiel, Game Over speichert den Bestwert und löscht den Stand, vor der Karte gleitet die Kamera auf das ausgefallene Gerät (neues Tippen überspringt, Loslassen eines schon liegenden Fingers nicht), Einstellungen wirken sofort und bleiben erhalten, Haptik folgt der Einstellung, Klänge (Kabel-Klick, Zupfen mit Dienst-Tonhöhe, eine Überlast-Warnung, Wochen-Glocke) folgen Spiel und Ton-Einstellung, Pause-Knopf während der Wochen-Belohnung, Neuheiten-Liste mit lokalem Trennzeichen, Zurück im Hauptmenü beendet, Uhrzeit auf 10 Minuten abgeschnitten und über Mitternacht umgebrochen, Tag/Nacht-Zeile im HUD erst mit einem Backup-Server, Deutsch als Standard/Fallback und Englisch per Systemsprache; „Spielen“ öffnet die Szenerie-Auswahl, gesperrte Karten starten nichts, Zurück führt ins Hauptmenü; ein Bestwert von 1.500 in der Kleinstadt öffnet die Großstadt (Jahr 1998, eigene Größe), Neu starten und Fortsetzen behalten die Szenerie; gekaufte Szenerien sind spielbar, gesperrte fragen den Shop; Bestwerte je Szenerie, „Nochmal“ bleibt in der Szenerie), `SoundAssetsTest` (WAVs in `res/raw` bitgleich mit `SoundSynth`, zusammen < 300 KB, nicht übersteuert, leiser Anfang und Ausklang), `ServicePitchTest` (eigener Pentatonik-Ton je Dienst, Raten 0,5–2), `ServiceColorsTest` (Farbenblind-Palette bleibt bei simulierter Prot-/Deuteranopie unterscheidbar (ΔE ≥ 25 für alle 7 Dienste), die Standard-Palette nicht; jede Dienstfarbe beider Paletten hält ΔE ≥ 30 Abstand zu DSL-/Koax-Kabel und Icon-Tinte, auch bei simulierter Farbenblindheit), `SceneryTest` (JVM: Deko deterministisch aus dem Seed, spärlich mit allen Arten, nie auf Wasser, Knoten oder Kabeln, weicht einem neuen Knoten und Kabel, von hinten nach vorn sortiert, Kachel-Variation im Bereich), `IsoGroundCacheTest` (gecachter Boden pixelgleich mit direkt gezeichnetem, neuer Knoten baut den Cache neu und entfernt die Deko, ein Bagger steht nie auf sichtbarer Deko, bewegte Kamera zeichnet direkt, bis sie stillsteht), `CameraTest` (JVM: Einpassen mit HUD-Rändern, sanftes Gleiten zu einem Punkt innerhalb von Zoom- und Pan-Grenzen, Hin-/Rückrechnung bei jedem Zoom/Pan, Zoom um den Pivot, Grenzen, Animation, Zwei-Finger-Geste), `RendererCameraTest` (beide Stile: Welt → Bildschirm → Welt inkl. Kamera, Startblick passt den Block ein, Zoom-Bereich, Mitwachsen nur ohne Spieler-Eingriff, Touch-Ziele ≥ 48 dp bei jedem Zoom und jeder Dichte), `GameViewGestureTest` (Ein-Finger-Kabel, zweiter Finger bricht ab und zoomt, Pan auf leerem Boden, Doppeltipp, WLAN-Knopf setzt AP, Tippen wechselt den Kanal, Halten schaltet auf 5 GHz, schon während der Finger liegt, Wegziehen bricht das Halten ab, Tippen auf ein gekapptes Kabel repariert es statt es zu entfernen), `RendererLayoutTest` (beide Stile liefern identische Kabelwege und Paketpositionen), `ScreenshotTest` (Robolectric rendert beide Stile, die Zieh-Vorschau und ein komplettes Spielbild mit HUD, die Wochen-Belohnung über der Iso-Karte, alle Belohnungskarten inkl. gedrückter Karte und WLAN-/Mast-Karte, Funk mit Interferenz, 5 GHz und Mast in beiden Stilen und mit HUD, dazu der Halte-Ring auf einem AP (`wireless-*.png`), Hin-/Rückweg mit Rechenzentrum in beiden Stilen (`round-trip-*.png`), die gewachsene Stadt eingepasst und ganz herausgezoomt (`map-grown-*.png`, `map-overview-*.png`), einen Pinch-Zoom (`camera-zoom-iso.png`), den Start einer neuen Partie im Iso-Stil (`new-game-iso.png`), Hauptmenü deutsch und englisch (`menu-main.png`, `menu-main-en.png`), Pause-Menü und Einstellungen (`menu-pause.png`, `menu-settings.png`), die Szenerie-Auswahl deutsch mit Fortschritt und Hinweis sowie englisch (`menu-sceneries.png`, `menu-sceneries-en.png`), jede Szenerie zum Start als Spielbild im Iso-Stil und als ganze Karte im Übersichtsmodus (`scenery-<id>.png`, `scenery-<id>-flat.png`), Game Over (`game-over.png`) und die Sekunde davor mit Kamera-Fokus auf das ausgefallene Gerät (`game-over-focus.png`), den Iso-Feinschliff aus der Nähe mit Deko, Schatten, Fluss-Glitzer, halb verlegtem Kabel, Ankunfts-Ringen und aufsteigenden Zustellungen (`iso-polish.png`), die Farbenblind-Palette (`palette-colorblind-iso.png`), die neuen Dienste nachts nach der Backup-Welle in beiden Stilen und mit HUD (`new-services-*.png`) Störungen mit gekapptem Kabel, angekündigtem Bagger, dunklem Router und angekündigtem Stromausfall am AP in beiden Stilen, herangezoomt und mit HUD (`incidents-*.png`) alle Dienst-Formen in beiden Paletten und alle Geräte-Icons (`service-shapes.png`) sowie das Tutorial Schritt für Schritt auf dem Handy und Schritt 1 englisch (`tutorial-*.png`) nach `docs/screenshots/`; Texte deutsch, Menüs als Querformat-Handy 800 × 360 dp) |

Die Stilstudie mit vier Looks (Flat, Iso, Pixel, Platine) liegt in `docs/style-explorations.html`.

### Dateien

```
core/src/main/kotlin/com/mininetworks/game/game/   (Gradle-Modul :core, reines Kotlin/JVM)
  Scenario.kt               Terrain, TerrainFeature, ScenarioRule, Unlock, Scenario, Scenarios (die 5 Szenerien, Gelände-Aufbau, Freischaltung)
  Model.kt                  Shape, Demand, Service, Device, CableType, Node (inkl. Footprint, Kanal), ServerUpgradeError, ConnectError, CableUpgradeError, WifiUpgradeError, WeekNews, Link, Cable, Packet (Anfrage/Antwort), Route, Geometry
  Radio.kt                  RadioType (WLAN-AP, Mobilfunkmast), Wifi (Kanäle, Interferenz-Formel), RadioLink
  CellRect.kt               Rechteckiger Feldblock (freigeschalteter Bereich, Wachsen um Ringe)
  CableLayout.kt            Cell, Bend, CableLayout (Kabelgeometrie auf dem Raster, Knick-Vorschlag aus der Zieh-Spur)
  World.kt                  Spielzustand, Regeln, Simulation, Routing
  Rewards.kt                Reward, RewardOffer, deterministische Auswahl der Wochen-Belohnungen
  Incidents.kt              IncidentKind, Incident, RepairError, Wochenplan der Störungen (Bagger, Stromausfall)
  FixedStep.kt              Fester Zeitschritt (1/60 s, Akkumulator, max. 5 Schritte pro Frame)
  Save.kt                   Save (JSON), WorldSnapshot und Teil-Snapshots
  ReplayableRandom.kt       Zufallsgenerator wie Random(seed), der sich speichern und wiederherstellen lässt
  DebugApi.kt               Opt-in-Markierung für Test-/Debug-Hooks
  SoundCues.kt              SoundCue, SoundCues (welche Ereignisse seit der letzten Abfrage klingen sollen)
  Tutorial.kt               TutorialStep, TutorialFocus, Tutorial (5 geführte Schritte als Zustandsmaschine über einer geführten Welt)
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
  NewServicesTest.kt        Videocall, Kamera-Upload (Stream), Cloud-Backup (nachts), Uhr, Server-Fahrplan
  IncidentsTest.kt          Störungen: Plan, Ankündigung, Wirkung, Reparatur, Speichern
  ScenarioTest.kt           Szenerien: Daten, Gelände, Epochen, Regeln, Freischaltung, Speichern
  EffectCuesTest.kt         Hinweise für Animationen: Kabel-Bauzeit, Ankünfte am Server und beim Gerät
  SoundCuesTest.kt          Klang-Hinweise: Zustellung je Dienst, Überlast-Warnung einmal je Überlast, Wochen-Glocke, stille Übernahme
  TutorialTest.kt           Tutorial: geführte Welt, Schritte und ihre Ziele, Epochen-Sprünge, Hervorhebung, Überspringen
app/src/main/java/com/mininetworks/game/
  MainActivity.kt           Vollbild-Activity, hostet GameView, startet/stoppt den Game-Thread, leitet Zurück weiter
  data/SaveStore.kt         Autosave-Datei in filesDir
  data/SettingsStore.kt     GameSettings in SharedPreferences
  data/HighscoreStore.kt    Bestwert je Szenerie und zuletzt gestartete Szenerie in SharedPreferences
  audio/SoundPlayer.kt      Sound, ServicePitch, SoundPlayer (SoundPool, Ton-Einstellung, Zupf-Abstand)
  monetization/Entitlements.kt  Kauf-Schnittstelle für Szenerien (bis P3.4: NoEntitlements)
  render/Renderer.kt        Renderer-Interface (Projektion in Map-Einheiten, Picking in Bildschirm-Pixeln), TouchTargets, DeviceIcons, CableStyles, RadioStyles, IncidentStyles, ServiceColors, Shapes
  render/Camera.kt          Camera (Zoom, Pan, Einpassen, Animation), TwoFingerGesture, MapRect, ViewInsets
  render/FlatRenderer.kt    Stil A
  render/IsoRenderer.kt     Stil B (Hauptstil): gecachte Bodenebene, Schatten, Deko, Wasser-Glitzer, Animationen
  render/Scenery.kt         Deko und Kachel-Variation, deterministisch aus Seed und Feld (reines Kotlin)
  ui/GameView.kt            SurfaceView, Game-Thread, Eingabe-Queue, HUD, Bildschirm-Wechsel (Menüs, Speichern, Einstellungen)
  ui/RewardDialog.kt        Wochen-Belohnung: zwei Karten im Iso-Look auf dem Canvas
  ui/TutorialOverlay.kt     Tutorial-Karte links (Text je Schritt, Überspringen, Abschluss) und pulsierende Hervorhebung
  ui/Texts.kt               Anzeigetexte zu den IDs der Logik (aus strings.xml)
  ui/menu/MenuPanel.kt      MenuPage, MenuItem, MenuAction und das Zeichnen der Menükarten
  ui/menu/Screen.kt         Hauptmenü, Szenerie-Auswahl, Spiel, Pause, Einstellungen, Game Over
  ui/menu/SceneryPicker.kt  Szenerie-Auswahl: Karten mit Iso-Vorschau, Schloss, Fortschritt
  ui/menu/DemoCity.kt       Feste Demo-Stadt hinter dem Hauptmenü
app/src/main/res/
  values/strings.xml        Deutsch (Standard)
  values-en/strings.xml     Englisch
  raw/sfx_*.wav             Klänge (Zupfen, Kabel-Klick, Warnung, Wochen-Glocke), erzeugt von SoundSynth
app/src/test/java/com/mininetworks/game/
  audio/SoundSynth.kt       Erzeugt die Klänge in res/raw (additive Synthese, deterministisch)
  audio/SoundAssetsTest.kt  Eingecheckte WAVs = Synth, < 300 KB, kein Übersteuern
  audio/ServicePitchTest.kt Ein eigener Pentatonik-Ton je Dienst im SoundPool-Bereich
  render/ScreenshotTest.kt  Rendert Szenen, die Zieh-Vorschau und das Spielbild mit HUD als PNG
  render/RendererLayoutTest.kt  Beide Stile lesen Kabelweg und Paketposition aus dem Modell
  render/CameraTest.kt      Kamera-Mathematik auf der JVM
  render/RendererCameraTest.kt  Projektion inkl. Kamera, Einpassen, Mitwachsen, Touch-Ziele
  render/ServiceColorsTest.kt  Farbenblind-Palette
  render/SceneryTest.kt     Deko: deterministisch, spärlich, nie auf Wasser, Knoten oder Kabeln
  render/IsoGroundCacheTest.kt  Boden-Cache: pixelgleich, Neuaufbau nach Kartenänderung und nach Kamera-Stillstand
  ui/GameViewGestureTest.kt Kabel ziehen, Pinch, Pan, Doppeltipp
  ui/TutorialFlowTest.kt    Tutorial in der App: erster Start, Überspringen, aus den Einstellungen, Touch, nie gespeichert
  ui/MenuFlowTest.kt        Menüs, Autosave, Fortsetzen, Bestwert, Einstellungen, Klänge, Sprache
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

Die Ping-Limits der Tabelle gelten für einen Weg; seit P1.2 zählt der Ping hin und zurück (im Code Telefonie 300 ms, Gaming 110 ms, Videocall 240 ms).
Umgesetzt in P2.2: Videocall (Laptop, Smartphone, Tablet), Kamera-Upload (nur Überwachungskamera, eine Anfrage alle 2,5 s),
Cloud-Backup (PC, Laptop, Smart-Home-Hub, nur um 02:00 Spielzeit). Uploads bekommen als Antwort nur eine kleine Quittung (Größe 1).

Die Formen bleiben die Hauptinformation (farbenblind-tauglich), die Farbe unterstützt nur.

### 3.2 Geräte und Epochen

| Epoche (Woche) | Jahr | Neue Geräte | Neue Technik | Neue Server |
|---|---|---|---|---|
| 1 | 1995 | PC, Telefon | ISDN | Mail, Vermittlung |
| 2 | 1998 | Laptop | DSL | – |
| 3 | 2001 | Konsole | TV-Kabel | Game-Server |
| 4 | 2004 | Smartphone, Smart-TV | – | Streaming-CDN |
| 5 | 2007 | Tablet | Glasfaser | – |
| 6 | 2010 | Smartwatch | WLAN-Access-Point (neu) | Videocall |
| 7 | 2013 | Kamera, Smart-Home (neu) | Mobilfunkmast 4G/5G (neu) | Kamera-Upload |
| 8 | 2016 | – | – | Cloud-Backup |
| 10+ | 2022+ | – | – | zufällig, jede zweite Woche |

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
4. **Störungen:** umgesetzt in P2.3: Ein Bagger kappt ein Kabel, ein Stromausfall legt einen Router oder WLAN-AP für 10 s lahm. Selten, angekündigt.
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
- **Audio:** `SoundPool` für kurze Klänge (Paket zugestellt = Ton nach Dienst, umgesetzt in P3.2), leise generative Musik später.
- **Haptik:** kurzes Feedback beim Einrasten eines Kabels.
- **Sprachen:** Deutsch und Englisch über `strings.xml`; die Logik liefert nur IDs, keine Texte.
- **Barrierefreiheit:** Formen tragen die Information, zusätzlich eine alternative Farbpalette für Farbenblindheit, skalierbare UI.

### 4.2 Bekannte Vereinfachungen im Prototyp (bewusst)

- Kein Sichtbarkeits-Culling: der Iso-Stil zeichnet immer alle 640 Kacheln (Performance-Check in Welle 4).
- Gelände liegt auf dem ganzen Raster, auch auf gesperrten Feldern. Berge und Hochhäuser liegen in der gecachten Bodenebene: ein Knoten direkt hinter einem hohen Gipfel oder Turm wird über ihn gezeichnet statt von ihm verdeckt.
- Die Vorschauen der Szenerie-Auswahl nutzen einen festen Seed: Flüsse und gestreutes Gelände sehen in der nächsten Partie ähnlich, aber nicht gleich aus.
- Keine Musik, nur Effekte; Klänge folgen dem Ton-Schalter, nicht einer eigenen Lautstärke (die Medienlautstärke des Systems regelt sie).
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
**P2.2 Neue Dienste: Videocall, Kamera-Upload, Cloud-Backup** · inkl. Icons, Farben, Tests · umgesetzt
- Stand: Dienste laut 3.1 mit den Formen Fünfeck, Sechseck, Plus; Geräte Überwachungskamera und Smart-Home-Hub (Woche 7) mit eigenen Icons;
  Server-Fahrplan Woche 6/7/8, danach zufällig ab Woche 10 (vorher: zufällig ab Woche 6). Festgelegt, wo 3.1 offen war:
  Videocall 240 ms für Hin- und Rückweg (120 ms je Weg), Kamera-Takt 2,5 s, ein Tag = 15 s Spielzeit, Backup-Welle um 02:00 mit 2 Anfragen je Gerät,
  Upload-Antworten sind Quittungen der Größe 1. Die Farbenblind-Palette wurde für 7 Dienste nachjustiert; ihr Test verlangt jetzt ΔE ≥ 25 statt 30
  (mit sieben Farben bei Dichromasie nicht erreichbar, die Formen tragen die Information). Keine Nacht-Abdunklung der Karte (war Kandidat für P2.4, dort nicht umgesetzt). Balancing in Welle 4.
**P2.3 Störungen: Bagger und Stromausfall** · Ankündigung, Effekt, Reparatur-Mechanik · umgesetzt
- Stand: Festgelegt, wo die Vorgabe offen war: Ankündigung 5 s für beide Arten, Reparatur kostet pauschal 3 Budget, Häufigkeit 1 pro Woche ab Woche 3,
  ab Woche 9 zwei, höchstens 2 (nach Review von anfangs 4 gesenkt, damit Störungen selten bleiben). Der Plan (Zeit, bevorzugte Art) hängt nur an Seed und Woche; das Ziel hängt zwangsläufig vom Netz des Spielers ab
  und wird mit einem eigenen, aus Seed und Woche abgeleiteten Zufallsstrom gezogen (der Spiel-Zufall wird nicht berührt). Stromausfälle treffen nur
  verkabelte Router und WLAN-APs (keine Masten, keine Server). Während der Wochen-Belohnung laufen die Countdowns nicht weiter.
  Während der Ankündigung lässt sich ein Umweg legen oder das Kabel entfernen (ruft den Bagger ab, ohne Erstattung); ein gekapptes Kabel kann man nicht per Tippen entfernen
  oder aufrüsten, der Tipp repariert es. Für Tests: `World.incidentsEnabled` und `announceExcavator`/`announcePowerOutage` (`@DebugApi`);
  Screenshots anderer Themen schalten Störungen ab, damit sie stabil bleiben. Balancing in Welle 4.
**P2.4 Isometrischen Stil ausbauen** (Entscheidung 5, Nr. 1): Gebäude-Details, Schatten, Bäume/Deko · Animationen beim Kabellegen, Paket-Zustellung, Game Over · umgesetzt
- Stand: Schatten, Kachel-Variation, Deko, Fluss-Glitzer, Kabel-Wachsen, Ankunfts-Ringe am Server mit hüpfendem Schild, aufsteigende Zustellungen
  am Gerät und Kamera-Fokus bei Game Over (siehe Abschnitt 2, „Iso-Feinschliff“). Die Deko wird nicht vorab von Spawn-Feldern ferngehalten
  (Geräte dürfen fast überall im Block entstehen), sondern verschwindet, sobald ein Knoten oder Kabel ihr Feld belegt. Deko und Schatten liegen
  in der gecachten Bodenebene unter Kabeln und Funkkreisen; Bäume und Häuser sind deshalb klein gehalten (Krone innerhalb der eigenen Kachel),
  ein Kabel über einem Schatten bleibt hell. Kabel-Wachsen und Ankünfte laufen in Spielzeit (`World.time`) und stehen daher in Pause und Wochen-Belohnung still; Glitzer und Game-Over-Wellen laufen in Animationszeit.
  Keine Nacht-Abdunklung und keine eigenen Gebäude-Details über die Schatten hinaus; der Flat-Stil bekommt nur den Kamera-Fokus.

### Welle 3 – parallel (3 Agenten)

**P3.1 Szenerien:** die 5 Szenerien aus 5.2 als Daten (JSON), Auswahlbildschirm mit Freischaltung · umgesetzt
- Stand: Daten als Kotlin in `:core` statt JSON in den Assets (typsicher, ohne Parser, in JVM-Tests direkt nutzbar). Festgelegt, wo 5.2 offen war:
  Gelände aus Merkmalen mit dem Seed statt fest gezeichneter Karten, Aufschläge Berg 3 und Hochhaus 1 je Feld, Sichtlinie für Funk, Startvorräte und Rastergrößen je Szenerie (siehe Abschnitt 2),
  Jahr ab Startjahr, Tempo/Wachstum/Störungen nach gespielten Wochen. „Hochhäuser verdecken Funk“ und „Berge blockieren Funk“ gelten für WLAN und Masten gleich.
  Nicht umgesetzt: Richtfunk (Insel), Satelliten und eigene 6G-Technik (2030 hat stattdessen die Regeln `SIX_G` und `ORBITAL_SERVERS`), echte Städte aus 3.5.
  Die Kauf-Schnittstelle `Entitlements` ist hier definiert, weil P3.4 noch fehlt; P3.4 setzt sie mit Play Billing um und reicht sie an `GameView.entitlements`. Balancing der Ziele und Startvorräte in Welle 4.
**P3.4 Monetarisierung:** `Monetization`-Interface, AdMob + UMP + Play Billing laut 5.1, nur Test-IDs
**P3.2 Audio und Haptik:** SoundPool, Töne je Dienst, Haptik-Feedback · umgesetzt
- Stand: siehe „Audio und Haptik“ in Abschnitt 2. WAV statt OGG: ein OGG-Encoder bräuchte eine neue Abhängigkeit, und die vier WAV-Dateien bleiben mit ~106 KB weit unter dem Budget von 300 KB.
  Ein Zupf-Sample für alle Dienste, die Tonhöhe kommt aus der Abspielrate. Der Haptik-Tick beim Einrasten bestand schon seit P1.4 und blieb.
**P3.3 Tutorial:** 5 geführte Schritte (Kabel legen, Router, Kabeltypen, Ping, Überlast) · umgesetzt
- Stand: siehe „Tutorial“ in Abschnitt 2. Festgelegt, wo die Vorgabe offen war: Das Tutorial ist eine eigene geführte Partie auf der Kleinstadt-Karte
  (nicht ein Overlay über der ersten echten Partie), weil DSL, Glasfaser und der Game-Server sonst erst nach Wochen kämen; es springt dafür per `World.advanceEra` nach 1998 und 2007.
  Schritt 4 nutzt den PC aus Schritt 1 statt einer Konsole, damit kein Gerät nebenher unbedient überläuft. Schritt 5 startet mit 6 wartenden Anfragen, damit der Ring sofort sichtbar ist.
  „Beim ersten Start“ heißt: Die App öffnet direkt im Tutorial, solange es nicht gesehen wurde und kein Spielstand existiert. Nach dem Abschluss führt „Spielen“ direkt in eine neue Kleinstadt-Partie.
  Kein Tutorial-Fortschritt über einen App-Neustart hinweg (es beginnt dann wieder bei Schritt 1). Screenshots: `tutorial-*.png`.

### Welle 4 – Release-Vorbereitung (nacheinander)

- Balancing-Durchlauf mit Bot: automatischer Greedy-Spieler in `:core`-Tests, der misst, wie lange er überlebt.
- Performance: 60 fps bei 60 Knoten und 200 Paketen auf einem Mittelklasse-Gerät.
- Play-Store: Icon, Screenshots, Datenschutzseite, signiertes AAB, interner Test-Track.

## 7. Startprompt für die Ultracode-Session

> Setze `docs/PLAN.md` im Repo `robinrehbein/mini-networks` um, beginnend mit Welle 0 und danach Welle 1.
> Halte dich an die Paket-Grenzen und die Definition von „fertig“ in Abschnitt 6.
> ultracode
