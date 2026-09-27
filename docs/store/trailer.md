# Mini Networks – Trailer (30 Sekunden)

Storyboard für den Store-Trailer (docs/TOP100.md F4; Team-Aufgabe 5 in Abschnitt 2). Ziel: in 30 Sekunden zeigen, was
man tut (Kabel ziehen), warum es spannend wird (Stau, Ping, Wachstum) und was einen zurückholt (Tagesaufgabe,
Szenerien). Ruhiger Ton wie das Spiel, kein Werbedruck, keine Kaufdialoge, keine Werbung im Bild.

## Rahmen

| Punkt | Vorgabe |
|---|---|
| Länge | 30 s (Play spielt YouTube-Videos; Hochladen als öffentliches oder nicht gelistetes YouTube-Video, Werbung aus) |
| Format | 1920 × 1080, 16:9 quer, 60 fps (das Spiel läuft in Querlage) |
| Aufnahme | Echte Spielszenen: Bildschirmaufnahme auf einem Handy (Pixel, 420 dpi) mit abgeschaltetem Tutorial-Hinweis und ohne Werbung (Debug-Build = `NoOpMonetization`), Touch-Punkte sichtbar (Entwickleroptionen „Berührungen anzeigen“) nur in Shot 2 |
| Szenen | Die festen Szenen aus den Screenshot-Tests (`Scenes.hud()`, `Scenes.wireless()`, `Scenes.incidents()`, Großstadt), damit Trailer, Screenshots und Store-Texte dasselbe zeigen |
| Ton | Die Spielsounds (`res/raw/sfx_*.wav`: Kabel, Zupfen, Warnung, Woche) über einer ruhigen Musik (lizenzfrei, 90–100 BPM, z. B. Marimba/Synth-Plucks); Schnitte auf den Takt |
| Text | Kurze Einblendungen im Stil der Screenshot-Beschriftungen (weiß, fett, auf dem Dämmerungsblau des Icons), je Sprache aus `docs/store/<sprache>.md` übersetzbar; höchstens 5 Wörter pro Tafel |
| Sicherheitsrand | Keine Schrift in den äußeren 10 % (YouTube- und Play-Bedienelemente) |
| Endbild | Icon, „Mini Networks“, Google-Play-Badge nach Googles Badge-Richtlinien (offizielles Badge, nicht nachgebaut) |

## Shotliste

| # | Zeit | Bild (Kamera) | Aktion im Spiel | Einblendung (DE / EN) | Ton |
|---|---|---|---|---|---|
| 1 | 0:00–0:03 | Kaltstart: nahe Iso-Aufnahme eines PCs und des Mail-Servers, langsames Heranzoomen | Eine Mail wartet am PC | „1995.“ / „1995.“ | Ein einzelner Pluck, Musik setzt ein |
| 2 | 0:03–0:07 | Gleiche Szene, Finger zieht vom PC zum Server | Kabel wächst mit Funken, rastet ein, erstes Paket fährt hin und zurück, Ring am Server | „Ein Wisch, ein Kabel“ / „One swipe, one cable“ | `sfx_cable`, dann `sfx_pluck` beim Zustellen |
| 3 | 0:07–0:11 | Zeitraffer herausgezoomt (Game-Over-Rückblick als Vorlage, `GrowthRecorder`) | Die Stadt wächst: DSL, Koaxkabel, Glasfaser, neue Geräte; Jahreszahl im HUD springt 1995 → 2010 | „30 Jahre Netztechnik“ / „30 years of network tech“ | Wochen-Jingle `sfx_week` auf dem Jahressprung |
| 4 | 0:11–0:14 | Halbnah auf Streaming-TV und Game-Server | Rote Stau-Ringe füllen sich; Spieler tippt Kabel an und rüstet auf Glasfaser auf, Lichtschein läuft entlang, Ringe leeren sich | „Jeder Dienst hat sein Tempo“ / „Every service has its pace“ | `sfx_warning` leise, dann Aufrüst-Pluck |
| 5 | 0:14–0:17 | Funk-Szene (`Scenes.wireless()`) von oben schräg | WLAN-Access-Point wird gesetzt, Kanal gewechselt (rote Überlappung verschwindet), Mobilfunkmast verbindet Smartphones | „WLAN, 4G und 5G“ / „Wi-Fi, 4G and 5G“ | zwei kurze Plucks |
| 6 | 0:17–0:20 | Großstadt, Zwei-Finger-Geste | Karte dreht sich stufenlos um 90° und rastet weich ein, Kompass erscheint | „Dreh die Karte“ / „Turn the map“ | Swoosh auf der Musik |
| 7 | 0:20–0:23 | Störungs-Szene (`Scenes.incidents()`) | Bagger kappt ein Kabel (roter Countdown), Spieler tippt → repariert | „Achtung, Bagger!“ / „Mind the excavator!“ | `sfx_warning`, dann Pluck |
| 8 | 0:23–0:26 | Schnelle Schnitte (je ~1 s) | Tagesaufgaben-Karte mit Serie, Wochen-Belohnung mit Konfetti, Szenerie-Auswahl mit fünf Karten | „Jeden Tag neu · 5 Szenerien“ / „New every day · 5 sceneries“ | `sfx_week` auf dem Konfetti |
| 9 | 0:26–0:30 | Endbild auf Dämmerungsblau, dahinter unscharf die gewachsene Stadt | – | Icon, „Mini Networks“, „Kostenlos bei Google Play“ / „Free on Google Play“ + Play-Badge | Musik schließt mit einem Akkord |

## Hinweise für Schnitt und Freigabe

- Keine Werbung, keine Kaufdialoge, keine Preise, keine Aussagen wie „Nr. 1“ (Play-Richtlinie Store-Assets).
- Der Hinweis „Kostenlos“ ist wahr, In-App-Käufe und Werbung stehen im Store-Eintrag; im Video nicht verschweigen,
  falls eine Plattform es verlangt (Untertitel „Enthält Werbung und In-App-Käufe“).
- Erste 3 Sekunden ohne Logo: Play spielt Trailer oft stumm im Autoplay – das Kabelziehen muss ohne Ton verständlich sein.
- Untertitel/Einblendungen für die 12 Sprachen des Store-Eintrags aus der jeweiligen Datei `docs/store/<sprache>.md`
  (Screenshot-Beschriftungen passen als Tafeltexte).
- Vorschau-Bild des Videos: Screenshot 1 (`docs/store/screenshots/phone/<sprache>/01-town.png`).
