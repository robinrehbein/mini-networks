# Balancing mit Bots (P4.1, T6)

Ein gieriger Spieler (`GreedyBot` in den `:core`-Tests) spielt jede Szenerie mit festen Seeds, bis ein Gerät
überläuft. Er misst, wie viele Wochen er überlebt und wie viele Pakete er zustellt (die Punktzahl des Spiels). Die Werte in
`World.Tuning`, `CableType`, `Service` und je Szenerie (`Scenarios`) sind so gestellt, dass der ausgewogene Bot in der
ersten Szenerie im Median 8–14 Wochen schafft und jede spätere Szenerie messbar schwerer ist; derselbe Bot im Tempo eines
Menschen (`BotStrategy.HUMAN`) schafft dort im Median mindestens 7 Wochen. Einseitige Bots (nur eine
Kabelsorte, nur Funk) zeigen, dass es keine dominante Strategie gibt, und ein Wochenplan (`WeekSchedule`) sorgt dafür, dass
jede Woche bis Woche 12 etwas Neues bringt.

## Ziele (docs/TOP100.md, Abschnitt G) und wie sie gemessen werden

| Ziel | Festlegung | Test |
|---|---|---|
| G1 Überlebenszeit | Median der Wochen des ausgewogenen Bots in der Kleinstadt über 20 Seeds (1–20, parallel gespielt) liegt in 8–14 | `BalancingTest.firstSceneryLastsEightToFourteenWeeks` |
| G1 „messbar schwerer“ | In Menü-Reihenfolge (Kleinstadt, Großstadt, Insel & Hafen, Bergdorf, Zukunft 2030) liegt der Median jeder Szenerie **mindestens 0,5 Wochen** unter dem der Szenerie davor (`HARDER_BY`) | `BalancingTest.everySceneryIsHarderThanTheOneBefore` |
| G2 „klar schlechter“ | Der Median der Punktzahl (zugestellte Pakete) **jedes** einseitigen Bots liegt **mindestens 20 % unter** dem des ausgewogenen Bots (`ONE_SIDED_GAP`), gemessen in der Kleinstadt und in Zukunft 2030 (dort ist jede Kabelsorte und jeder Funk ab dem Start da, kein einseitiger Bot wird vom Kalender benachteiligt); die Tabelle unten zeigt alle Szenerien | `BalancingTest.oneSidedBotsScoreClearlyLower` |
| Fair für Menschen (T-Human) | Der Bot im Spieltempo (`BotStrategy.HUMAN`, eine Entscheidung alle 2,5 s) schafft in der Kleinstadt im Median über 20 Seeds **mindestens 7 Wochen** (`HUMAN_MIN_MEDIAN_WEEKS`) und verliert mit den Wächter-Seeds 1–3 keine Partie vor Woche 4 | `BalancingTest.humanBotLastsLongEnough` |
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

## Was in T-Human geändert wurde

Rückmeldung aus dem Probespiel: „Es ist sehr schwer, überhaupt weit zu kommen.“ Die Schwierigkeit war nur am ausgewogenen Bot
gestellt, der alle 0,5 s die ganze Karte sieht. Entscheidung: keine Schwierigkeitsstufen, sondern „Normal“ fair für Menschen.
Gemessen mit dem Bot im Spieltempo (`BotStrategy.HUMAN`, siehe unten); Ziel: Kleinstadt im Median 7–10 Wochen, kein Wächter-Seed
vor Woche 4 verloren, Reihenfolge der Szenerien (G1) und G2 halten.

Der Bot im Spieltempo kam schon vorher auf 9,2 Wochen: Langsamkeit allein erklärt den Frust nicht. Ein Mensch übersieht aber ein
neues Gerät, reagiert später auf eine Störung und rettet ein Gerät erst, wenn sein Ring schon fast voll ist. Die Änderungen zielen
darum auf genau diese Momente und lassen das Spätspiel (Staus ab Woche 8–10), an dem beide Bots scheitern, wie es ist.

| Wert | vorher | jetzt | Warum |
|---|---|---|---|
| Fairer Start `EARLY_WEEKS` (Geräte nur, wo direkt erreichbar; Ringe füllen sich halb so schnell) | 2 Wochen | 3 Wochen | Woche 3 war eine Klippe: Schonfrist vorbei, erste Störungen, Konsolen mit 140 ms Gaming und der erste Kartenring kamen zusammen. Woche 3 bringt jetzt nur noch Konsole, Gaming und den Kartenring, noch mit Schonfrist. Dafür endet die Schonfrist in Woche 4, zusammen mit der ersten Störung, Streaming und Fernsehern (siehe nächste Zeile): Die Klippe ist eine Woche später und kleiner, aber nicht aufgelöst |
| Erste Störungen `Incidents.FIRST_WEEK` | Woche 3 | Woche 4 (zwei je Woche ab Woche 10 statt 9) | Wie oben: Woche 3 bringt mit TV-Kabel, Konsole und Gaming schon genug; G3 hält, Woche 4 bringt dafür Fernseher, Streaming **und** die erste Störung, dazu volles Ring-Tempo. Gemessen, um das zu entzerren: erste Störung in Woche 5 macht die Insel (9,5 Wochen) leichter als die Großstadt (9,0); eine auslaufende Schonfrist in Woche 4 (Ringe 1,5- bzw. 1,25-mal langsamer) hebt Zukunft 2030 auf 6,4 Wochen, nicht mehr 0,5 unter dem Bergdorf (6,7); beides verletzt G1. Erste Störung schon in Woche 3 (noch in der Schonfrist) hält G1, senkt aber den Bot im Spieltempo (Kleinstadt 9,4, Insel 5,6, Zukunft 2030 4,5 Wochen, schlechter als vor T-Human). Darum bleibt es bei Woche 4; die längere Vorwarnung gilt ab der ersten Störung |
| Vorwarnung `Incidents.WARNING_SECONDS` | 5 s | 8 s | Ein Mensch muss die Ankündigung erst sehen, dann das Kabel finden und einen Umweg legen; 5 s reichten dem Bot, kaum einem Spieler. Für die Bots fast ohne Wirkung |
| Ring leeren `RECOVER_SECONDS` | 30 s | 15 s | Ein Gerät, das man im letzten Moment rettet, war noch eine halbe Minute lang gefährdet: Die nächste Spitze schloss den Ring trotzdem. Die Bot-Mediane ändern sich dadurch nicht messbar (beide Bots retten selten erst im letzten Moment), für Menschen ist es der häufigste Fall. Gewollte Nebenwirkung: Ein Gerät, das immer wieder an der Grenze `MAX_PENDING` hängt, läuft erst über, wenn es mehr als 15 / (15 + 18) ≈ 45 % der Zeit dort hängt (vorher 18 / 48 = 37,5 %), in den Wochen mit Schonfrist erst ab 36 / 51 ≈ 70 % (vorher 36 / 66 ≈ 55 %). Ein dauerhaft knappes Kabel hält also etwas länger durch, bevor man aufrüsten muss |
| Anfragen ohne Route (`World.queueHasRoom`) | stauten sich ohne Ende | höchstens bis `MAX_PENDING`, weitere gehen verloren | Ein vergessenes Gerät sammelte Dutzende Anfragen; wurde es dann verkabelt, lief sein Ring weiter, bis der ganze Rückstau abgearbeitet war, und der Stoß verstopfte das neue Kabel. Jetzt bringt die erste gesendete Anfrage es unter die Grenze. Ein nie verbundenes Gerät erreicht die Grenze weiter und läuft genauso schnell über wie vorher: Geräte zu ignorieren lohnt sich nicht (`WorldTest.unroutedRequestsStopAtTheQueueLimit`). Anfragen *mit* Route stauen sich weiter unbegrenzt, das ist der Stau, den der Ring misst |
| Überlast-Zeit `OVERLOAD_SECONDS` | 18 s | 18 s (unverändert) | Gemessen: 22 s (bzw. 25 s) hoben den Bot im Spieltempo in der Kleinstadt auf 10,3 (10,4) Wochen, über das Ziel von höchstens 10, und halfen den späten Szenerien mehr als der ersten (Zukunft 2030 5,8 → 6,5 bzw. 7,2 Wochen, vor dem Bergdorf mit 6,8: G1 verletzt). Die Schonfrist der ersten drei Wochen verdoppelt die Zeit ohnehin dort, wo Menschen am meisten verlieren |
| `MAX_PENDING` | 6 | 6 (unverändert) | Nicht nötig; jede weitere Verlängerung hätte wie oben die Reihenfolge der Szenerien verschoben |
| Freischalt-Ziele `METROPOLIS_TARGET` / `ISLAND_TARGET` | 1.000 / 650 | 1.000 / 650 (unverändert) | Weiter höchstens der Median der Szenerie davor (1.273 bzw. 949 Pakete, etwa das 0,8- bzw. 0,7-Fache). Ein höheres Insel-Ziel würde Spielern, die die Insel schon freigespielt haben, sie wieder sperren (die Freischaltung wird aus dem Bestwert berechnet) und wäre für Menschen schwerer, nicht leichter |

Mediane über 20 Seeds (Wochen / Pakete):

| Szenerie | ausgewogen vorher | ausgewogen jetzt | Spieltempo vorher | Spieltempo jetzt |
|---|---|---|---|---|
| river_town | 10,2 / 1.261 | 10,1 / 1.273 | 9,2 / 1.059 | 9,8 / 1.112 |
| metropolis | 8,2 / 808 | 8,9 / 949 | 8,0 / 701 | 9,3 / 990 |
| island_harbor | 6,9 / 567 | 8,1 / 729 | 6,9 / 467 | 6,9 / 483 |
| mountain_village | 6,3 / 465 | 6,7 / 518 | 6,5 / 507 | 6,6 / 532 |
| future_2030 | 5,1 / 440 | 5,8 / 583 | 4,8 / 301 | 5,9 / 518 |

Die späteren Szenerien gewinnen mehr als die Kleinstadt: Sie starten mit mehr Geräten und Diensten, die ruhigen ersten drei Wochen
wiegen dort schwerer. Die Reihenfolge hält für beide Bots (ausgewogen: Abstände 1,2 / 0,8 / 1,4 / 0,9 Wochen; im Spieltempo
0,5 / 2,4 / 0,3 / 0,7). Der kürzeste Kleinstadt-Lauf im Spieltempo dauert 6,2 Wochen, die Wächter-Seeds 1–3 halten 10,2 / 8,6 / 9,3
Wochen. G2 hält: Der beste einseitige Bot liegt in der Kleinstadt 81 %, in Zukunft 2030 59 % unter dem ausgewogenen. Die Grenzen von
G1 (8–14 Wochen) blieben unverändert.

## Was in T-Clutter geändert wurde

Rückmeldung vom Handy: „Es werden zu schnell zu viele Elemente auf dem Bildschirm, man kann die Kabel nicht mehr voneinander
trennen.“ Gemessen mit dem Bot im Spieltempo in der Kleinstadt (20 Seeds, Mittel je Wochenanfang; vor T-Clutter):

| Woche | Geräte | Kabel | Felder mit 2+ Kabeln | höchster Stapel auf einem Feld | Karte | Geräte je Feld |
|---|---|---|---|---|---|---|
| 2 | 6,1 | 9,7 | 6 | 3,3 | 16×10 | 0,04 |
| 4 | 13,3 | 26,2 | 24 | 4,5 | 18×12 | 0,06 |
| 6 | 21,0 | 38,5 | 39 | 5,3 | 20×14 | 0,08 |
| 8 | 29,2 | 52,4 | 58 | 6,3 | 22×16 | 0,08 |
| 10 | 38,5 | 65,2 | 70 | 6,8 | 24×18 | 0,09 |

Zwei Ursachen, zwei Maßnahmen:

1. **Kabel lagen exakt übereinander.** Ab Woche 4 laufen im Mittel 4–7 Kabel über dasselbe Feld, und man sah nur das oberste.
   Jetzt bekommt jeder gerade Abschnitt eine eigene Spur (`CableLanes`, wie die Linien in Mini Metro): Kabel über denselben
   Feldkanten laufen nebeneinander, höchstens `MAX_LANES` = 4 Spuren mit `SPACING` = 0,30 Feld Abstand, ein einzelnes Kabel bleibt in
   der Mitte seiner Felder. Ein verlegtes Kabel behält seine Spur, wenn ein neues dazukommt. Das ändert nur das Bild und den Weg
   der Pakete (`Cable.path`), nicht Routen, Kosten oder Speicherstand.
2. **Die Geräte kamen schneller, als die Karte wächst** (0,04 Geräte je Feld in Woche 2, 0,09 in Woche 10), und die Kamera zeigt
   immer die ganze Karte, die Felder werden also zusätzlich kleiner. Jetzt erscheint kein neuer Kunde mehr, solange die Karte
   `Scenario.clientDensity` Kunden je freigeschaltetem Feld trägt (`World.isCrowded`, Prüfung alle 2 s); jeder neue Ring macht
   Platz für ein paar weitere. Die Tagesregel „Andrang“ hat keine Grenze.

| Szenerie | `clientDensity` | Warum |
|---|---|---|
| Kleinstadt | 0,07 | Hier fiel das Problem auf; die Karte bleibt auf dem Handy lesbar |
| Großstadt | 0,075 | Etwas dichter, damit sie weiter 0,5 Wochen schwerer als Insel & Hafen bleibt (G1) |
| Insel & Hafen, Bergdorf | 0,09 | Wirkt praktisch nicht: beide scheitern vor der Dichtegrenze; so bleiben ihre Werte unverändert |
| Zukunft 2030 | 0,10 | Wie oben |

Folge für die Werte (ausgewogener Bot / Bot im Spieltempo, Wochen): Kleinstadt 10,1 → 13,0 / 9,8 → 11,8, Großstadt 8,9 → 9,1 /
9,3 → 9,5, die übrigen unverändert. Das Spiel wird in der Kleinstadt also noch etwas leichter, die Reihenfolge der Szenerien (G1)
und G2 halten. Beachten: Die Punktzahlen der Kleinstadt steigen (Median 1273 → etwa 1900), die Freischalt-Ziele
(`Scenarios.METROPOLIS_TARGET` 1000, `ISLAND_TARGET` 650) sind damit lockerer als die geplanten 0,8 Mediane.

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
| 3 | 2001 | TV-Kabel | Konsole | Gaming | – |
| 4 | 2004 | – | Fernseher | Streaming | erste Störungen (1 je Woche) |
| 5 | 2007 | WLAN, Mobilfunkmast 3G (1 geschenkt) | Smartphone | – | – |
| 6 | 2010 | Glasfaser, 4G/LTE | Tablet | – | – |
| 7 | 2013 | – (Masten jetzt auch als Belohnung) | – | Videocall | – |
| 8 | 2016 | – | Smartwatch | – | – |
| 9 | 2019 | 5G | Kamera | Kamera-Upload | – |
| 10 | 2022 | – | – | Backup | 2 Störungen je Woche |
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
| river_town | 13,0 | 6,4–25,0 | 1898 | 472–8180 | 2/20 | TV:STREAMING:jam (4×) |
| metropolis | 9,1 | 4,8–13,1 | 970 | 259–2070 | 0/20 | TV:STREAMING:jam (4×) |
| island_harbor | 8,1 | 3,3–14,2 | 727 | 121–2488 | 0/20 | TV:STREAMING:jam (7×) |
| mountain_village | 6,7 | 2,8–17,1 | 518 | 88–3946 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (3×) |
| future_2030 | 5,8 | 2,0–11,8 | 583 | 63–2309 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (8×) |
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
| river_town | 1898 (13,0 W.) | 119 (3,3 W., −94 %) | 233 (4,4 W., −88 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) |
| metropolis | 970 (9,1 W.) | 57 (2,6 W., −94 %) | 246 (4,7 W., −75 %) | 477 (6,3 W., −51 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) |
| island_harbor | 727 (8,1 W.) | 13 (1,7 W., −98 %) | 244 (5,0 W., −66 %) | 345 (5,5 W., −53 %) | 0 (1,7 W., −100 %) | 1 (1,7 W., −100 %) |
| mountain_village | 518 (6,7 W.) | 25 (1,8 W., −95 %) | 279 (4,8 W., −46 %) | 138 (3,3 W., −73 %) | 0 (1,7 W., −100 %) | 0 (1,7 W., −100 %) |
| future_2030 | 583 (5,8 W.) | 23 (1,5 W., −96 %) | 152 (3,1 W., −74 %) | 239 (3,7 W., −59 %) | 56 (1,9 W., −90 %) | 29 (1,8 W., −95 %) |
<!-- strategies:end -->

Vor T6 (alte Werte und alter Bot, 20 Seeds): Kleinstadt 7,4 / Großstadt 6,7 / Insel 5,4 / Bergdorf 4,8 / Zukunft 2030 4,1 Wochen,
667 / 533 / 339 / 258 / 275 Pakete; der beste einseitige Bot (nur DSL) kam in der Kleinstadt auf 225 Pakete.

## Menschliches Tempo (Bot mit 2,5 s Reaktionszeit)

Der ausgewogene Bot schaut alle 0,5 s auf die ganze Karte und handelt sofort an allen Stellen – schneller als jeder Mensch.
`BotStrategy.HUMAN` (`human`) entscheidet genauso, aber im Tempo eines Spielers: Er schaut nur alle 2,5 s Spielzeit auf die Karte,
trifft dabei höchstens **eine** Entscheidung (ein Kabel samt der Aufrüstungen seiner Route, ein Router mit seinen Kabeln, ein Funkknoten,
eine Server- oder Mast-Aufrüstung, eine Reparatur), repariert gekappte Kabel erst, wenn sie seit 3 s gekappt sind, und wählt die
Wochenbelohnung erst nach 2 s (die Welt steht dabei still, das kostet also keine Spielzeit). Er gehört nicht zu den einseitigen Bots
von G2; seine Kleinstadt-Partien prüft der Wächter `humanBotLastsLongEnough`, die übrigen Szenerien spielt nur der Bericht. 20 Seeds je Szenerie, höchstens 25 Wochen:

<!-- human:start -->
| Szenerie | Wochen (Median) | Wochen (Min–Max) | Pakete (Median) | Pakete (Min–Max) | bis Woche 25 | häufigstes Ende |
|---|---|---|---|---|---|---|
| river_town | 11,8 | 6,6–25,0 | 1594 | 485–6728 | 1/20 | TV:STREAMING:jam (6×) |
| metropolis | 9,5 | 5,8–17,2 | 980 | 338–4212 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (8×) |
| island_harbor | 6,9 | 3,3–10,8 | 483 | 119–1412 | 0/20 | TV:STREAMING:jam (4×) |
| mountain_village | 6,6 | 2,9–9,9 | 532 | 108–1154 | 0/20 | LAPTOP:CALL:unrouted (4×) |
| future_2030 | 5,9 | 3,8–12,1 | 518 | 220–1795 | 0/20 | CAMERA:CAMERA_UPLOAD:jam (9×) |
<!-- human:end -->

## Wächter

`BalancingTest.botSurvivesTheFirstSceneryLongEnough` verlangt in der Kleinstadt mit den Seeds 1–3 mindestens 4 Wochen,
`noSceneryIsLostInTheFirstWeeks` in **jeder** Szenerie (auch den gekauften) mit denselben Seeds mindestens 2 Wochen; mit 20 Seeds
liegt das Minimum heute bei 2,0 (Zukunft 2030) bis 5,9 Wochen (Kleinstadt). Beide lesen die geteilten Partien der G1-Tests.
`humanBotLastsLongEnough` verlangt dasselbe (mindestens 4 Wochen mit den Seeds 1–3) vom Bot im Spieltempo und dazu einen
Kleinstadt-Median von mindestens 7 Wochen; er spielt dafür nur die 20 Kleinstadt-Partien des Bots im Spieltempo, die der Bericht weiterverwendet.
Die drei Kleinstadt-Läufe heute:

<!-- guard:start -->
| Seed | Wochen | Pakete | Ende |
|---|---|---|---|
| 1 | 15,8 | 2875 | CAMERA:CAMERA_UPLOAD:jam |
| 2 | 7,1 | 533 | SMARTPHONE:MAIL:unrouted |
| 3 | 9,9 | 939 | TV:STREAMING:jam |
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
