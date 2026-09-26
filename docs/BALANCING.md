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
| Ping-Limit Gaming | 110 ms | 140 ms | Konsolen kommen in Woche 3, Glasfaser erst in Woche 5. Mit 110 ms und TV-Kabel reichte Gaming nur ~7 Felder weit, die meisten Konsolen liefen in Woche 4 über. Jetzt reicht TV-Kabel ~14 Felder, weiter braucht es weiter Glasfaser |
| Neue Server `SERVER_AREA` (neu) | irgendwo im Block | im mittleren Drittel des Blocks | Ein Game-Server in einer Ecke war für die halbe Stadt mit keinem Kabel erreichbar |
| Großstadt | 30 Budget, 3 Router | 56 Budget, 4 Router | Kurve: etwas schwerer als die Kleinstadt |
| Insel & Hafen | 36 Budget, 2 Router | 62 Budget, 2 Router | Kurve |
| Bergdorf | 34 Budget, 2 Router | 60 Budget, 2 Router | Kurve |
| Zukunft 2030 | 48 Budget, 3 Router | 74 Budget, 4 Router | Mit sieben Servern ab Start fehlen sonst Router; bleibt die schwerste Szenerie |

Router sind für den Bot der stärkste Hebel (ein Router mehr bringt in den späteren Szenerien 1–2 Wochen), das Start-Budget der schwächste;
darüber ist die Kurve zwischen den Szenerien gestellt. Das Tutorial legt den Game-Server dafür weiter weg (15 Felder), damit dort TV-Kabel
weiterhin zu langsam ist und nur Glasfaser reicht.

Vorher (alte Werte, gleicher Bot, 20 Seeds): der Bot überlebte in **jeder** Szenerie im Median nur 0,9–1,2 Wochen,
in der Kleinstadt fast immer wegen eines Telefons, das mit ISDN nicht erreichbar war, in Insel und Bergdorf wegen Konsolen ohne erreichbaren Game-Server.

## Ergebnisse

20 Seeds je Szenerie, höchstens 25 Wochen. „Wochen“ zählt ab dem Start der Szenerie, mit Bruchteil. „Ende“: Gerät, Dienst und Grund
(`unrouted`: keine Route, `ping`: Route zu langsam, `jam`: Route da, aber Stau).

<!-- summary:start -->
| Szenerie | Wochen (Median) | Wochen (Min–Max) | Pakete (Median) | Pakete (Min–Max) | bis Woche 25 | häufigstes Ende |
|---|---|---|---|---|---|---|
| river_town | 6,8 | 3,4–8,1 | 553 | 155–855 | 0/20 | CAMERA:CAMERA_UPLOAD:unrouted (4×) |
| metropolis | 5,9 | 2,9–8,7 | 431 | 109–931 | 0/20 | TV:STREAMING:jam (5×) |
| island_harbor | 5,0 | 2,9–9,9 | 274 | 89–1502 | 0/20 | TV:STREAMING:jam (5×) |
| mountain_village | 4,5 | 1,5–7,7 | 241 | 29–841 | 0/20 | CONSOLE:GAMING:unrouted (8×) |
| future_2030 | 3,4 | 0,9–8,0 | 163 | 17–925 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (6×) |
<!-- summary:end -->

## Wächter

`BalancingTest.botSurvivesTheFirstSceneryLongEnough` spielt bei jedem Build die Kleinstadt mit den Seeds 1–3 und verlangt
mindestens 4 Wochen. So fällt ein versehentlicher Schwierigkeitssprung sofort auf. Die drei Läufe heute:

<!-- guard:start -->
| Seed | Wochen | Pakete | Ende |
|---|---|---|---|
| 1 | 6,7 | 579 | WATCH:CALL:ping |
| 2 | 7,0 | 565 | CAMERA:CAMERA_UPLOAD:unrouted |
| 3 | 4,9 | 288 | PHONE:CALL:unrouted |
<!-- guard:end -->

## Neu messen

```bash
BALANCING_REPORT=1 ./gradlew :core:test --tests '*BalancingTest*'   # schreibt die Tabellen oben neu
BALANCING_REPORT=1 BALANCING_SEEDS=40 ./gradlew :core:test --tests '*BalancingTest*'
```

Die Läufe sind deterministisch (fester Seed, fester Zeitschritt 1/60 s). Einzelne Seeds streuen stark (siehe Min–Max),
darum zählt der Median.
