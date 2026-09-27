# Mini Networks – Store-Einträge (docs/TOP100.md F2, F3, F4)

Ein Eintrag je Sprache der App (12, docs/TOP100.md F1). Zeichenzahlen sind Unicode-Zeichen, wie die Play Console zählt;
`app/src/test/.../i18n/StoreListingTest.kt` prüft Grenzen (Titel ≤ 30, Kurzbeschreibung ≤ 80, Beschreibung ≤ 4000),
die angegebenen Zahlen und dass die Screenshot-Beschriftungen denen des Screenshot-Tests entsprechen. Der Titel trägt einen
Suchbegriff hinter dem App-Namen („Mini Networks: …“); wer nur den reinen Namen will, trägt `Mini Networks` ein.
Die Beschreibungen nennen nur, was das Spiel wirklich hat (Stand T8: 5 Szenerien, 39 Erfolge, 3 Modi, Tagesaufgabe,
Play Games optional, Werbung nur zwischen Partien, „Werbefrei“ und Szenerien als einmalige Käufe).

| Sprache | Datei | Titel | Titel | Kurz | Lang |
|---|---|---|---:|---:|---:|
| Deutsch | [`de-DE.md`](de-DE.md) | Mini Networks: Netz-Puzzle | 26 | 74 | 2171 |
| English | [`en-US.md`](en-US.md) | Mini Networks: Network Puzzle | 29 | 79 | 2103 |
| Français | [`fr-FR.md`](fr-FR.md) | Mini Networks : puzzle réseau | 29 | 70 | 2215 |
| Español | [`es-ES.md`](es-ES.md) | Mini Networks: puzle de redes | 29 | 66 | 2211 |
| Italiano | [`it-IT.md`](it-IT.md) | Mini Networks: puzzle di reti | 29 | 66 | 2204 |
| Português (Brasil) | [`pt-BR.md`](pt-BR.md) | Mini Networks: puzzle de redes | 30 | 63 | 2207 |
| Polski | [`pl-PL.md`](pl-PL.md) | Mini Networks: łamigłówka | 25 | 70 | 2139 |
| Nederlands | [`nl-NL.md`](nl-NL.md) | Mini Networks: netwerkpuzzel | 28 | 67 | 2115 |
| Türkçe | [`tr-TR.md`](tr-TR.md) | Mini Networks: Ağ Bulmacası | 27 | 70 | 2074 |
| 日本語 | [`ja-JP.md`](ja-JP.md) | Mini Networks：ネットワークパズル | 23 | 40 | 1027 |
| 한국어 | [`ko-KR.md`](ko-KR.md) | Mini Networks: 네트워크 퍼즐 | 22 | 43 | 1115 |
| 简体中文 | [`zh-CN.md`](zh-CN.md) | Mini Networks：网络布线益智 | 20 | 36 | 781 |

**Grafiken** (Robolectric, `app/src/test/.../render/StoreScreenshotTest.kt`):

- `screenshots/<gerät>/<sprache>/01-town.png` … `08-incidents.png`, je 8 für `phone` (1920 × 1080, 420 dpi),
  `tablet-7` (1920 × 1080, xhdpi, 960 × 540 dp) und `tablet-10` (2560 × 1440, xhdpi); 16:9 und 24-Bit-PNG ohne Alpha,
  wie Play es für Telefone und Tablets verlangt (1080–7680 px je Seite). Eingecheckt: Deutsch und Englisch; weitere
  Sprachen mit `-Pstore.locales=fr,es,it,pt-rBR,pl,nl,tr,ja,ko,zh-rCN`.
- `feature-graphic.png`: 1024 × 500, 24-Bit-PNG, nichts Wichtiges in den äußeren 10 %: links eine dämmerblaue Fläche
  mit Glasfaser-Kante, App-Icon, Name und „1995 → 2030“ (Zahlen, passt zu jedem Eintrag), rechts ein Knoten mit
  leuchtendem Server und einem Gerät in Überlast. `feature-graphic/<sprache>.png`: dieselbe Grafik mit der Tagline der Sprache („von ISDN bis
  Glasfaser“, wie das Spiel 1995 mit ISDN beginnt), für jede erzeugte Sprache; in der Play Console je Sprache hochladen.
- Vor dem Start (docs/RELEASE.md 4): die übrigen 10 Sprachen mit `-Pstore.locales=fr,es,it,pt-rBR,pl,nl,tr,ja,ko,zh-rCN`
  erzeugen und je Eintrag hochladen; sonst zeigt Play dort die Standardbilder mit deutscher bzw. englischer
  Beschriftung. Nicht eingecheckt, weil jeder Satz rund 8 MB PNG ist (zehn Sprachen ≈ 80 MB bei jedem Neuerzeugen).
- Eine Vorlage für alle Bilder: das Spiel randlos unter einem dämmerblauen Beschriftungsband mit Glasfaser-Kante,
  App-Icon und kurzer, einzeiliger Überschrift.
- Motive: 1 lebendige Stadt (wenige Anfragen), 2 Kabel ziehen mit Vorschau, 3 Überlastung im echten Spielbild mit HUD
  (glaubwürdiges Budget, pulsierender roter Schein, Rand abgedunkelt), 4 gedrehte dichte Stadt 2030 mit
  Zwei-Finger-Geste, 5 Funk (Abdeckung mit Signalwellen), 6 Wochen-Belohnung mit Konfetti nur am Rand, 7 fünf
  Szenerien mit kleinem Tagesaufgaben-Abzeichen, 8 Bagger und Stromausfall.
- `screenshots/phone-portrait/<sprache>/`: 1080 × 1920 für das Hochformat-Karussell von Play, alle Motive außer 6
  (die zwei Belohnungskarten brauchen Breite).
- Trailer: `trailer.md`.

Erzeugen:
`ROBOLECTRIC_DEPS_DIR=/opt/robolectric ./gradlew testDebugUnitTest --tests '*StoreScreenshotTest*'`
