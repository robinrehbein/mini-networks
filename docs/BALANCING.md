# Balancing mit Bot (P4.1)

Ein einfacher gieriger Spieler (`GreedyBot` in den `:core`-Tests) spielt jede Szenerie mit festen Seeds, bis ein Gerät
überläuft. Er misst, wie viele Wochen er überlebt und wie viele Pakete er zustellt. Die Werte in `World.Tuning`,
`CableType`, `Service` und je Szenerie (`Scenarios`) sind so gestellt, dass der Bot in der ersten Szenerie etwa
6–10 Wochen schafft und jede spätere Szenerie schwerer ist.

## Der Bot

Er nutzt nur die Spieler-Aktionen von `World` und schaut alle 0,5 s Spielzeit auf die Karte:

- Jeder Server bekommt einen Router direkt daneben, solange Router im Vorrat sind; die Router werden zu einem Rückgrat verbunden.
- Ein Gerät ohne Route zu einem gewünschten Dienst bekommt das günstigste Kabel (samt der Aufrüstungen, die die Route dann braucht),
  das Bandbreite und Ping schafft: vom Gerät oder einem Knoten in seiner Nähe mit freiem Port zum nächsten passenden Router oder Server.
  Ziele, die mehr Dienste des Geräts erreichen, sind ihm etwas mehr wert. Liegt das Ziel weit weg und ist ein Router da, kommt ein neuer Router neben das Gerät.
- Scheitert eine Route an Bandbreite oder Ping, rüstet er ihre Kabel auf. Staut sich ein Gerät, rüstet er das schmalste Kabel der Route auf
  oder legt einen schnelleren Weg um das vollste Kabel.
- Server mit Warteschlange oder ohne freien Port rüstet er auf; gekappte Kabel repariert er; Funk aus Belohnungen stellt er dorthin, wo er die meisten Geräte erreicht.
- Belohnungen: Server-Gutschein, wenn ein Server ausgelastet ist, Router, wenn keiner mehr da ist, sonst Budget.
- Er baut nie ein Kabel ab, hängt keine Geräte an andere Geräte und nutzt keine Extras (Weiterspielen, +1 Router).

Ein Mensch baut sauberere Netze; der Bot ist die untere Messlatte.

## Was geändert wurde

| Wert | vorher | jetzt | Warum |
|---|---|---|---|
| Wochenlohn `WEEK_BUDGET` (neu) | – | +60 Budget je Wochenwechsel | Mit nur 24 Start-Budget und +16 als *eine* von zwei Belohnungen war nach 1–2 Wochen kein Geld für neue Geräte da. Der Bot braucht 50–60 Budget je Woche; der Wochendialog nennt den Lohn |
| `START_BUDGET` / `START_ROUTERS` | 24 / 2 | 50 / 3 | Die ersten drei Geräte und die Router an den beiden Servern kosten schon fast das alte Startgeld |
| Tempo neuer Geräte `SPAWN_SECONDS` − `SPAWN_SPEEDUP` × Woche | 11 − 1,2 × Woche (mind. 4 s) | 13 − 0,7 × Woche (mind. 4 s) | Die Stadt wächst langsamer, das Ende kommt über viele Wochen statt in Woche 3 |
| Anfragetakt `REQUEST_SECONDS` − `REQUEST_SPEEDUP` × Woche | 5,5 − 0,35 × Woche (mind. 1,6 s) | 7 − 0,2 × Woche (mind. 1,6 s) | Lange Kabel tragen wenige Pakete pro Sekunde; mit dem alten Takt stauten sich schon kleine Netze |
| Latenz je Feld ISDN / DSL / TV-Kabel / Glasfaser | 22 / 11 / 8 / 2,5 ms | 14 / 7 / 5 / 2,5 ms | Mit 22 ms erreichte ein Telefon den Telefonie-Server (300 ms) nur bis ~6 Felder: Telefone in der anderen Ecke des Startblocks waren in Woche 1 nicht zu retten. DSL bleibt doppelt so schnell wie ISDN |
| Ping-Limit Gaming | 110 ms | 140 ms | Konsolen kommen in Woche 3, Glasfaser erst in Woche 5 (seit dem Erfindungs-Fahrplan Woche 6). Mit 110 ms und TV-Kabel reichte Gaming nur ~7 Felder weit, die meisten Konsolen liefen in Woche 4 über. Jetzt reicht TV-Kabel ~14 Felder, weiter braucht es weiter Glasfaser |
| Neue Server `SERVER_AREA` (neu) | irgendwo im Block | im mittleren Drittel des Blocks | Ein Game-Server in einer Ecke war für die halbe Stadt mit keinem Kabel erreichbar |
| Großstadt | 30 Budget, 3 Router | 56 Budget, 4 Router | Kurve: etwas schwerer als die Kleinstadt |
| Insel & Hafen | 36 Budget, 2 Router | 62 Budget, 2 Router | Kurve |
| Bergdorf | 34 Budget, 2 Router | 54 Budget, 2 Router | Kurve; mit 60 verlor der Bot einzelne Seeds schon in Woche 2 (Game-Server mit vier belegten Ports), mit 54 baut er anders und hält überall mindestens 2,5 Wochen |
| Zukunft 2030 | 48 Budget, 3 Router | 74 Budget, 4 Router | Mit sieben Servern ab Start fehlen sonst Router; bleibt die schwerste Szenerie |

| Fairer Start `EARLY_WEEKS` (neu) | – | 2 Wochen | In den ersten zwei Wochen erscheinen Geräte nur dort, wo jeder ihrer Dienste mit einem direkten Kabel einer erfundenen Technik erreichbar ist: breit genug, höchstens 80 % des Ping-Limits (Platz für einen Router) und höchstens 24 Budget. Vorher endete Zukunft 2030 in einzelnen Seeds nach 0,9 Wochen |
| Überlast in den ersten Wochen `EARLY_OVERLOAD_SLOWDOWN` (neu) | – | Ring füllt sich halb so schnell | Schonfrist bis zum ersten Wochenlohn: der Bot war in Zukunft 2030 und Bergdorf früh pleite, bevor die +60 kamen |
| Freischalt-Ziele `METROPOLIS_TARGET` / `ISLAND_TARGET` | 1.500 / 3.000 Pakete | 900 / 650 Pakete | Etwa das 1,3-Fache des Bot-Medians in der Szenerie davor (damals 709 bzw. 507, mit dem Erfindungs-Fahrplan 667 bzw. 533); 1.500 und 3.000 lagen über dem besten Bot-Lauf und wirkten wie eine Bezahlschranke |
| Erfindungs-Fahrplan (`unlockWeek`, `serverWeek`) | Glasfaser Woche 5, WLAN 6, Mast 7, zuletzt Kamera und Smart-Home in Woche 7, Backup-Server Woche 8; ab Woche 9 nur noch Zufalls-Server | jede Woche bis 2026 bringt etwas: Smartphone und WLAN 5, Glasfaser und Tablet 6, Mast und Videocall 7, Smartwatch 8, Kamera 9, Backup 10, Smart-Home 11, ab 12 Zufalls-Server; Zukunft 2030 startet in Woche 12 | Der Kalender läuft bis 2026, die Neuheiten endeten aber 2016. Späte Erfindungen machen die ersten Wochen etwas leichter (Glasfaser kommt eine Woche später, dafür auch Tablet, Videocall und Kamera); Kleinstadt 7,4 statt 7,5 Wochen, Bergdorf 4,8 statt 4,0 |
| Geräte-Gewicht `DEVICE_WEIGHT_WEEKS` (neu) | 1 + Erfindungswoche | 1 + Erfindungswoche, höchstens bis Woche 8 | Neuere Geräte kommen weiter häufiger, aber Kamera und Smart-Home (Woche 9 und 11) würden sonst in Zukunft 2030 jedes dritte Gerät stellen |

Router sind für den Bot der stärkste Hebel (ein Router mehr bringt in den späteren Szenerien 1–2 Wochen), das Start-Budget der schwächste;
darüber ist die Kurve zwischen den Szenerien gestellt. Das Tutorial legt den Game-Server dafür weiter weg (15 Felder), damit dort TV-Kabel
weiterhin zu langsam ist und nur Glasfaser reicht.

Vorher (alte Werte, gleicher Bot, 20 Seeds): der Bot überlebte in **jeder** Szenerie im Median nur 0,9–1,2 Wochen,
in der Kleinstadt fast immer wegen eines Telefons, das mit ISDN nicht erreichbar war, in Insel und Bergdorf wegen Konsolen ohne erreichbaren Game-Server.

## Ergebnisse

20 Seeds je Szenerie, höchstens 25 Wochen. „Wochen“ zählt ab dem Start der Szenerie, mit Bruchteil. „Ende“: Gerät, Dienst und Grund
(`World.failure`: `unrouted`: keine Route, `narrow`: nur über zu schmale Leitungen, `ping`: Route zu langsam, `jam`: Route da, aber Stau).

<!-- summary:start -->
| Szenerie | Wochen (Median) | Wochen (Min–Max) | Pakete (Median) | Pakete (Min–Max) | bis Woche 25 | häufigstes Ende |
|---|---|---|---|---|---|---|
| river_town | 7,4 | 5,5–9,8 | 667 | 363–1206 | 0/20 | CONSOLE:GAMING:unrouted (7×) |
| metropolis | 6,7 | 3,0–9,4 | 533 | 86–1095 | 0/20 | CONSOLE:GAMING:unrouted (7×) |
| island_harbor | 5,4 | 3,3–8,0 | 339 | 125–652 | 0/20 | CONSOLE:GAMING:unrouted (3×) |
| mountain_village | 4,8 | 2,7–7,3 | 258 | 98–599 | 0/20 | CONSOLE:GAMING:unrouted (6×) |
| future_2030 | 4,1 | 2,0–8,5 | 275 | 59–1054 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (6×) |
<!-- summary:end -->

## Wächter

`BalancingTest.botSurvivesTheFirstSceneryLongEnough` spielt bei jedem Build die Kleinstadt mit den Seeds 1–3 und verlangt
mindestens 4 Wochen. So fällt ein versehentlicher Schwierigkeitssprung sofort auf. `noSceneryIsLostInTheFirstWeeks`
verlangt zusätzlich in **jeder** Szenerie (auch den gekauften) mit denselben Seeds mindestens 2 Wochen; mit 40 Seeds
liegt das Minimum heute bei 1,3 (Zukunft 2030) bis 3,4 Wochen (Kleinstadt). Zukunft 2030 streut dort stark: schon ein anders
gewichtetes Gerät verschiebt einzelne Seeds zwischen 1,3 und 2,4 Wochen (auch mit dem alten Fahrplan); die Seeds 1–3 halten alle mindestens 2 Wochen. Die drei Kleinstadt-Läufe heute:

<!-- guard:start -->
| Seed | Wochen | Pakete | Ende |
|---|---|---|---|
| 1 | 7,9 | 764 | TABLET:STREAMING:unrouted |
| 2 | 8,5 | 907 | WATCH:CALL:unrouted |
| 3 | 6,9 | 589 | CONSOLE:GAMING:unrouted |
<!-- guard:end -->

## Neu messen

```bash
BALANCING_REPORT=1 ./gradlew :core:test --tests '*BalancingTest*'   # schreibt die Tabellen oben neu
BALANCING_REPORT=1 BALANCING_SEEDS=40 ./gradlew :core:test --tests '*BalancingTest*'
```

Die Läufe sind deterministisch (fester Seed, fester Zeitschritt 1/60 s). Einzelne Seeds streuen stark (siehe Min–Max),
darum zählt der Median.
