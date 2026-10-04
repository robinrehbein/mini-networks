# Mini Networks

Natives Android-Spiel (Kotlin, ohne Engine) im Stil von Mini Metro / Mini Motorways – nur mit Netzwerken:
Geräte fordern Dienste an, der Spieler verlegt Kabel von ISDN bis Glasfaser und hält Bandbreite und Ping im Griff.

- **Plan und Spieldesign:** [`docs/PLAN.md`](docs/PLAN.md)
- **Stilstudie (4 Looks) und Prototyp-Screenshots:** [`docs/style-explorations.html`](docs/style-explorations.html)

![Isometrischer Stil](docs/screenshots/prototype-iso.png)

![Hauptmenü](docs/screenshots/menu-main.png)

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Steuerung:** Von Gerät/Server/Router zu einem anderen Knoten ziehen = Kabel legen ·
Kabeltechnik in der Leiste unten unter „Kabel“ wählen · Kabel antippen = auf gewählte Technik upgraden, sonst auswählen (nochmal antippen = entfernen) ·
ausgewähltes Kabel neu verlegen: Griff an einem Ende auf einen anderen Knoten ziehen, die Mitte ziehen = Knick umlegen
(bezahlt/erstattet wird nur die Preisdifferenz) · Kabel lange drücken = auswählen und nächstes Ende greifen ·
Router, WLAN und Mast unter „Netzwerk“ aufs Feld ziehen (oder antippen, dann ein freies Feld antippen) ·
die Punkte unter jedem Gerät sind seine Anschlüsse (● belegt, ○ frei: PC 2, Server 4, Router 6); sind sie voll,
kommt ein Router dazwischen · Server antippen = Vorschau mit Preis, nochmal antippen = aufrüsten ·
gekapptes Kabel antippen = reparieren · Gerät antippen = warum es hängt (seine Server leuchten auf) ·
WLAN antippen = Kanal wechseln, halten = 5 GHz · Mast antippen = Vorschau, nochmal = nächste Generation ·
„Pause“ hält die Uhr an (weiterbauen geht), Menü-Knopf oder Zurück öffnet das Pause-Menü ·
„?“ unten öffnet die Legende („Was ist was?“) direkt aus dem Spiel ·
Hinweise: abgelehnte Aktionen stehen auf einer roten Platte mit „!“, einmalige Tipps erklären eine Mechanik, wenn sie
zum ersten Mal zählt, und die Game-Over-Karte nennt neben dem Grund einen Tipp für die nächste Partie ·
Kamera: ziehen oder zwei Finger = verschieben, spreizen = zoomen, zwei Finger drehen = Karte drehen (rastet in
45°-Schritten ein: vier Ecken, vier Seiten), zwei Finger nebeneinander hoch/runter ziehen = flacher/steiler neigen;
die Knöpfe oben rechts drehen um 45° (⟲ ⟳), der Kompass dreht zurück nach Norden, die Würfel neigen flacher/steiler
(sie blenden sich ein paar Sekunden nach der letzten Kamerabewegung aus und kommen beim Verschieben zurück) ·
Doppeltipp = spielbare Fläche einpassen ·
der flache Übersichtsmodus, Ton, Haptik und eine Farbenblind-Palette stehen in den Einstellungen.
Das Spiel speichert automatisch beim Pausieren und Verlassen; „Fortsetzen“ im Hauptmenü lädt den Stand.

**Welches Gerät braucht welchen Server?** Jeder Dienst hat seinen eigenen Server-Typ (Mail-Server, Telefonzentrale,
Game-Server, Streaming-Server, Video-Server, Kamera-Cloud, Backup-Server); ein Rechenzentrum ist nur die höchste
Ausbaustufe eines dieser Server, kein eigener Typ. Jeder Server trägt ein Namensschild mit dem Symbol seines Dienstes –
demselben, das die Anfragen der Geräte zeigen. Zieht man ein Kabel von einem Gerät (oder tippt es an), leuchten die
passenden Server auf und die übrigen treten zurück; die Legende („Was ist was?“) listet Gerät → Server.

![Server-Namensschilder](docs/screenshots/servers-labels-iso.png)
![Passende Server beim Ziehen](docs/screenshots/servers-highlight-drag-iso.png)
