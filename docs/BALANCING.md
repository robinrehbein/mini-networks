# Balancing mit Bots (P4.1, T6)

Ein gieriger Spieler (`GreedyBot` in den `:core`-Tests) spielt jede Szenerie mit festen Seeds, bis ein Gerät
überläuft. Er misst, wie viele Wochen er überlebt und wie viele Pakete er zustellt (die Punktzahl des Spiels). Die Werte in
`World.Tuning`, `CableType`, `Service` und je Szenerie (`Scenarios`) sind so gestellt, dass der ausgewogene Bot in der
ersten Szenerie im Median 8–14 Wochen schafft und jede spätere Szenerie messbar schwerer ist. Einseitige Bots (nur eine
Kabelsorte, nur Funk) zeigen, dass es keine dominante Strategie gibt, und ein Wochenplan (`WeekSchedule`) sorgt dafür, dass
jede Woche bis Woche 12 etwas Neues bringt.

## Ziele (docs/TOP100.md, Abschnitt G) und wie sie gemessen werden

| Ziel | Festlegung | Test |
|---|---|---|
| G1 Überlebenszeit | Median der Wochen des ausgewogenen Bots in der Kleinstadt über 20 Seeds (1–20, parallel gespielt) liegt in 8–14 | `BalancingTest.firstSceneryLastsEightToFourteenWeeks` |
| G1 „messbar schwerer“ | In Menü-Reihenfolge (Kleinstadt, Großstadt, Insel & Hafen, Bergdorf, Zukunft 2030) liegt der Median jeder Szenerie **mindestens 0,5 Wochen** unter dem der Szenerie davor (`HARDER_BY`) | `BalancingTest.everySceneryIsHarderThanTheOneBefore` |
| G2 „klar schlechter“ | Der Median der Punktzahl (zugestellte Pakete) **jedes** einseitigen Bots liegt **mindestens 20 % unter** dem des ausgewogenen Bots (`ONE_SIDED_GAP`), gemessen in der Kleinstadt und in Zukunft 2030 (dort ist jede Kabelsorte und jeder Funk ab dem Start da, kein einseitiger Bot wird vom Kalender benachteiligt); die Tabelle unten zeigt alle Szenerien | `BalancingTest.oneSidedBotsScoreClearlyLower` |
| G3 Wochen-Inhalte | Jede Woche 1–12 bringt eine Technik, ein Gerät, einen Dienst oder ein Ereignis (`WeekSchedule.content`, den auch `World` beim Wochenwechsel nutzt) | `WeekScheduleTest` |

Die Läufe sind deterministisch; die ausgewogenen Partien werden einmal je Testlauf gespielt und von den Tests geteilt
(zusammen etwa 14 s auf 4 Kernen).

## Die Bots

`GreedyBot(world, strategy)`; `BotStrategy.BALANCED` ist der ausgewogene Bot. Er nutzt nur die Spieler-Aktionen von `World` und schaut alle 0,5 s Spielzeit auf die Karte:

- Jeder Server bekommt einen Router direkt daneben, solange Router im Vorrat sind; die Router werden zu einem Rückgrat verbunden.
- Ein Gerät ohne Route zu einem gewünschten Dienst bekommt das günstigste Kabel (samt der Aufrüstungen, die die Route dann braucht),
  das Bandbreite und Ping schafft: vom Gerät oder einem Knoten in seiner Nähe mit freiem Port zum nächsten passenden Router oder Server.
  Ziele, die mehr Dienste des Geräts erreichen, sind ihm etwas mehr wert. Liegt das Ziel weit weg und ist ein Router da, kommt ein neuer Router neben das Gerät.
- **Neu in T6:** Findet er so kein Ziel (alle Router- und Server-Ports belegt, keine Router mehr im Vorrat), hängt er das Gerät an ein
  Nachbargerät mit freiem Port, das den Dienst schon erreicht (Geräte leiten weiter, siehe `World.routeFor`). Vorher endete fast jede
  zweite Partie mit „Konsole ohne Route“, während Budget übrig war.
- Scheitert eine Route an Bandbreite oder Ping, rüstet er ihre Kabel auf. Staut sich ein Gerät, rüstet er das schmalste Kabel der Route auf
  oder legt einen schnelleren Weg um das vollste Kabel.
- Server mit Warteschlange oder ohne freien Port rüstet er auf; gekappte Kabel repariert er; Funk aus Belohnungen stellt er dorthin, wo er die meisten Geräte erreicht.
- Belohnungen: Server-Gutschein, wenn ein Server ausgelastet ist; Router, wenn weniger als 2 im Vorrat sind oder er 60 Budget oder mehr hat
  (**neu in T6**, vorher nur bei 0 Routern); sonst Budget.
- Er baut nie ein Kabel ab und nutzt keine Extras (Weiterspielen, +1 Router).

Die einseitigen Bots (G2) sind derselbe Bot mit Einschränkungen:

| Bot | Einschränkung |
|---|---|
| `only_isdn`, `only_dsl`, `only_coax`, `only_fiber` (`BotStrategy.singleCable`) | legt und rüstet nur auf diese eine Kabelsorte, ab der Woche, in der sie erfunden ist; sonst wie der ausgewogene Bot |
| `wireless_only` (`BotStrategy.WIRELESS_ONLY`) | verkabelt nie ein Gerät; Kabel nur zwischen Routern, Servern und Funkknoten. Nimmt als Belohnung zuerst Access Points und Masten und stellt Funk schon für ein einziges noch nicht versorgtes Gerät auf |

Ein Mensch baut sauberere Netze; der ausgewogene Bot ist die untere Messlatte.

## Was in T6 geändert wurde

| Wert | vorher | jetzt | Warum |
|---|---|---|---|
| Bot: Geräte an Geräte, Router-Vorrat | – | siehe oben | Kleinstadt-Median 7,4 → 7,9 Wochen mit den alten Werten; die Partien enden danach nicht mehr an fehlenden Ports, sondern an Staus und leerem Budget |
| Wochenlohn `WEEK_BUDGET` | 60 | 80 | Am Ende jeder Partie war das Budget leer: gestaute Kabel ließen sich nicht mehr aufrüsten. Allein brachte das +0,3 (Zukunft 2030) bis +1,9 Wochen (Großstadt) |
| Tempo neuer Geräte `SPAWN_SPEEDUP` | 0,7 s je Woche | 0,5 s je Woche | Die Stadt wächst in den späten Wochen langsamer (Woche 10: alle 8 s statt alle 6 s ein Gerät) |
| Anfragetakt `REQUEST_SPEEDUP` | 0,2 s je Woche | 0,15 s je Woche | Wie oben für die Anfragen; zusammen mit dem Wochenlohn: Kleinstadt 7,9 → 10,0 Wochen |
| Bergdorf Start | 54 Budget, 2 Router | 60 Budget, 1 Router | Mit den neuen Werten lag das Bergdorf (7,9 Wochen) vor der Insel (7,0). Ein Router weniger ist der verlässlichste Hebel (Budget streut in beide Richtungen); jetzt 6,0 Wochen, auch mit den Seeds 41–80 (6,3 gegenüber 8,4 auf der Insel) |
| Freischalt-Ziele `METROPOLIS_TARGET` / `ISLAND_TARGET` | 900 / 650 Pakete | 1.600 / 1.100 Pakete | Wieder etwa das 1,3-Fache des Bot-Medians der Szenerie davor (jetzt 1.226 bzw. 827); 900 lag unter dem neuen Median |
| Freischalt-Ziele `METROPOLIS_TARGET` / `ISLAND_TARGET` (Gesamt-Review nach T9) | 1.600 / 1.100 Pakete | 1.000 / 650 Pakete | Das 1,3-Fache des Medians war eine Wand: die meisten Bot-Partien (und vermutlich die meisten neuen Spieler) erreichten es nie, während dieselbe Szenerie daneben im Shop stand – das las sich wie eine Bezahlschranke. Jetzt etwa das 0,8-Fache des Bot-Medians der Szenerie davor (1.226 bzw. 827): eine ordentliche Partie schaltet frei, die Szenerie-Karte zeigt den Fortschritt als Balken („640 / 1.000 Pakete“). Wächter `BalancingTest.scoreUnlocksAreWithinReachOfAMedianRun`: Ziel ≤ Median und mindestens die Hälfte der 20 Bot-Partien erreicht es. Partien mit Extras (Weiterspielen, Bonus-Router) setzen keinen Bestwert und schalten also nichts frei (`FairPlayTest`) |
| Wochenplan `WeekSchedule` (neu) | in `World.onNewWeek` verstreut | eigenes Objekt, `World` nutzt es | Der Test für G3 prüft den Plan, den das Spiel wirklich spielt |

Mit 40 Seeds (1–40) liegen die Mediane bei 9,7 / 8,9 / 7,5 / 6,0 / 5,1 Wochen, mit den Seeds 41–80 bei 10,2 / 9,2 / 8,4 / 6,3 / 5,1
(Bergdorf-Wert der zweiten Reihe nach der Änderung): die Reihenfolge hält auch mit anderen Seeds.

## Was jede Woche bringt (G3)

`WeekSchedule` für eine Partie ab Woche 1 (Kleinstadt; spätere Szenerien starten mitten im Kalender mit allem bis dahin Erfundenen):

| Woche | Jahr | Technik | Gerät | Dienst (Server) | Ereignis |
|---|---|---|---|---|---|
| 1 | 1995 | ISDN | PC, Telefon | Mail, Telefonie | – |
| 2 | 1998 | DSL | Laptop | – | – |
| 3 | 2001 | TV-Kabel | Konsole | Gaming | erste Störungen (1 je Woche) |
| 4 | 2004 | – | Fernseher | Streaming | – |
| 5 | 2007 | WLAN, Mobilfunkmast 3G (1 geschenkt) | Smartphone | – | – |
| 6 | 2010 | Glasfaser, 4G/LTE | Tablet | – | – |
| 7 | 2013 | – (Masten jetzt auch als Belohnung) | – | Videocall | – |
| 8 | 2016 | – | Smartwatch | – | – |
| 9 | 2019 | 5G | Kamera | Kamera-Upload | 2 Störungen je Woche |
| 10 | 2022 | – | – | Backup | – |
| 11 | 2024 | – | Smart-Home | – | – |
| 12 | 2026 | – | – | Server eines Zufallsdienstes (dann jede zweite Woche) | – |

Dazu wächst die Karte in den Wochen 3, 5, 7, 9 und 11 um einen Ring (nicht mitgezählt). Jede Woche bis 12 bringt also sogar eine Technik, ein Gerät oder einen Dienst; `WeekScheduleTest.theFirstSceneryAnnouncesNewsEveryWeek`
spielt die Wochenwechsel 2–12 einer echten Partie und prüft, dass jeder davon eine frische Neuigkeit meldet.

## Ergebnisse

20 Seeds je Szenerie und Bot, höchstens 25 Wochen. „Wochen“ zählt ab dem Start der Szenerie, mit Bruchteil. „Ende“: Gerät, Dienst und Grund
(`World.failure`: `unrouted`: keine Route, `narrow`: nur über zu schmale Leitungen, `ping`: Route zu langsam, `jam`: Route da, aber Stau).

Ausgewogener Bot (G1):

<!-- summary:start -->
| Szenerie | Wochen (Median) | Wochen (Min–Max) | Pakete (Median) | Pakete (Min–Max) | bis Woche 25 | häufigstes Ende |
|---|---|---|---|---|---|---|
| river_town | 10,0 | 4,8–13,9 | 1226 | 300–2817 | 0/20 | TV:STREAMING:jam (4×) |
| metropolis | 8,4 | 5,1–11,9 | 827 | 284–1782 | 0/20 | TV:STREAMING:jam (5×) |
| island_harbor | 7,0 | 3,4–15,1 | 569 | 117–2348 | 0/20 | TV:STREAMING:jam (7×) |
| mountain_village | 6,0 | 2,4–11,0 | 434 | 66–1635 | 0/20 | CONSOLE:GAMING:unrouted (4×) |
| future_2030 | 5,1 | 2,0–10,7 | 440 | 61–1874 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (13×) |
<!-- summary:end -->

Punktzahl (Median der zugestellten Pakete) des ausgewogenen und der einseitigen Bots, dahinter Median der Wochen und der Abstand zum
ausgewogenen Bot (G2). Nur ISDN ist für Telefonie über längere Wege zu langsam und für Streaming (3 Einheiten) zu schmal, nur DSL
für Gaming zu langsam; nur TV-Kabel oder Glasfaser hat in den ersten Wochen gar nichts zu legen, und Funk kommt nur als Belohnung
(Access Points ab Woche 5, Masten ab 7; der erste 3G-Mast kommt in Woche 5 geschenkt), höchstens einer je Woche. Auch in Zukunft 2030, wo alles ab dem Start da ist, bleibt jeder
einseitige Bot weit zurück: nur Glasfaser ist zu teuer, nur Funk reicht für zu wenige Geräte, nur TV-Kabel oder DSL staut die Kameras.
Dass `wireless_only`, `only_coax` und `only_fiber` in der Kleinstadt schon in Woche 2 verlieren, liegt am Kalender (vor Woche 3, 5 bzw. 6
gibt es ihr Werkzeug nicht), nicht an einer schwachen Umsetzung; darum prüft der Test G2 zusätzlich in Zukunft 2030.

<!-- strategies:start -->
| Szenerie | ausgewogen | only_isdn | only_dsl | only_coax | only_fiber | wireless_only |
|---|---|---|---|---|---|---|
| river_town | 1226 (10,0 W.) | 100 (3,0 W., −92 %) | 216 (4,1 W., −82 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) |
| metropolis | 827 (8,4 W.) | 47 (2,3 W., −94 %) | 230 (4,4 W., −72 %) | 408 (5,8 W., −51 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) |
| island_harbor | 569 (7,0 W.) | 13 (1,7 W., −98 %) | 161 (3,9 W., −72 %) | 349 (5,5 W., −39 %) | 0 (1,7 W., −100 %) | 1 (1,7 W., −100 %) |
| mountain_village | 434 (6,0 W.) | 25 (1,8 W., −94 %) | 211 (4,1 W., −51 %) | 93 (2,8 W., −78 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) |
| future_2030 | 440 (5,1 W.) | 22 (1,5 W., −95 %) | 113 (2,3 W., −74 %) | 115 (2,7 W., −74 %) | 32 (1,7 W., −93 %) | 28 (1,8 W., −94 %) |
<!-- strategies:end -->

Vor T6 (alte Werte und alter Bot, 20 Seeds): Kleinstadt 7,4 / Großstadt 6,7 / Insel 5,4 / Bergdorf 4,8 / Zukunft 2030 4,1 Wochen,
667 / 533 / 339 / 258 / 275 Pakete; der beste einseitige Bot (nur DSL) kam in der Kleinstadt auf 225 Pakete.

## Wächter

`BalancingTest.botSurvivesTheFirstSceneryLongEnough` verlangt in der Kleinstadt mit den Seeds 1–3 mindestens 4 Wochen,
`noSceneryIsLostInTheFirstWeeks` in **jeder** Szenerie (auch den gekauften) mit denselben Seeds mindestens 2 Wochen; mit 40 Seeds
liegt das Minimum heute bei 1,7 (Zukunft 2030) bis 4,6 Wochen (Kleinstadt). Beide lesen die geteilten Partien der G1-Tests.
Die drei Kleinstadt-Läufe heute:

<!-- guard:start -->
| Seed | Wochen | Pakete | Ende |
|---|---|---|---|
| 1 | 9,7 | 1118 | PHONE:CALL:jam |
| 2 | 6,6 | 524 | SMARTPHONE:MAIL:unrouted |
| 3 | 9,5 | 1088 | SMARTPHONE:MAIL:unrouted |
<!-- guard:end -->

## Neu messen

```bash
BALANCING_REPORT=1 ./gradlew :core:test --tests '*BalancingTest*'   # schreibt die Tabellen oben neu
BALANCING_REPORT=1 BALANCING_SEEDS=40 ./gradlew :core:test --tests '*BalancingTest*'
```

Die Läufe sind deterministisch (fester Seed, fester Zeitschritt 1/60 s). Einzelne Seeds streuen stark (siehe Min–Max),
darum zählt der Median.

## Frühere Änderungen (P4.1)

Der Stand vor T6; die Zahlen in dieser Tabelle gelten für die damaligen Werte.

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
| Mobilfunk-Generationen (`CellGeneration`) | ein Mast ab Woche 7 mit Kapazität 8 und 15 ms, nur als Belohnung | Mast ab Woche 5 (2007, mit dem Smartphone) als 3G (Kapazität 3, 35 ms), 4G/LTE ab Woche 6 (8, 15 ms, Aufrüsten 8), 5G ab Woche 9 (14, 6 ms, Aufrüsten 12); in Woche 5 gibt es einen 3G-Mast geschenkt (`FIRST_CELL_TOWERS`), als Belohnung weiter erst ab Woche 7 (`CELL_TOWER_REWARD_WEEK`) | Wunsch aus dem Probespiel: WLAN, 3G, 4G/LTE und 5G unterscheiden. Die Mast-Belohnung schon ab Woche 5 verdrängte andere Belohnungen und machte Insel & Hafen (7,0 → 6,1 Wochen) schwerer als das Bergdorf (G1 verletzt); mit dem geschenkten Mast und der Belohnung ab Woche 7 bleibt G1 grün. Alte Spielstände: Masten funken mit 4G (die bisherigen Werte) |
| Erfindungs-Fahrplan (`unlockWeek`, `serverWeek`) | Glasfaser Woche 5, WLAN 6, Mast 7, zuletzt Kamera und Smart-Home in Woche 7, Backup-Server Woche 8; ab Woche 9 nur noch Zufalls-Server | jede Woche bis 2026 bringt etwas: Smartphone und WLAN 5, Glasfaser und Tablet 6, Mast und Videocall 7, Smartwatch 8, Kamera 9, Backup 10, Smart-Home 11, ab 12 Zufalls-Server; Zukunft 2030 startet in Woche 12 | Der Kalender läuft bis 2026, die Neuheiten endeten aber 2016. Späte Erfindungen machen die ersten Wochen etwas leichter (Glasfaser kommt eine Woche später, dafür auch Tablet, Videocall und Kamera); Kleinstadt 7,4 statt 7,5 Wochen, Bergdorf 4,8 statt 4,0 |
| Geräte-Gewicht `DEVICE_WEIGHT_WEEKS` (neu) | 1 + Erfindungswoche | 1 + Erfindungswoche, höchstens bis Woche 8 | Neuere Geräte kommen weiter häufiger, aber Kamera und Smart-Home (Woche 9 und 11) würden sonst in Zukunft 2030 jedes dritte Gerät stellen |

Router sind für den Bot der stärkste Hebel (ein Router mehr bringt in den späteren Szenerien 1–2 Wochen), das Start-Budget der schwächste;
darüber ist die Kurve zwischen den Szenerien gestellt. Das Tutorial legt den Game-Server dafür weiter weg (15 Felder), damit dort TV-Kabel
weiterhin zu langsam ist und nur Glasfaser reicht.

Vorher (alte Werte, gleicher Bot, 20 Seeds): der Bot überlebte in **jeder** Szenerie im Median nur 0,9–1,2 Wochen,
in der Kleinstadt fast immer wegen eines Telefons, das mit ISDN nicht erreichbar war, in Insel und Bergdorf wegen Konsolen ohne erreichbaren Game-Server.
