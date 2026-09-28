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

- `screenshots/<gerät>/<sprache>/01-town.png` … `08-sceneries.png`, je 8 für `phone` (1920 × 1080, 420 dpi),
  `tablet-7` (1920 × 1080, xhdpi, 960 × 540 dp) und `tablet-10` (2560 × 1440, xhdpi); 16:9 und 24-Bit-PNG ohne Alpha,
  wie Play es für Telefone und Tablets verlangt (1080–7680 px je Seite). Eingecheckt: Deutsch und Englisch; weitere
  Sprachen mit `-Pstore.locales=fr,es,it,pt-rBR,pl,nl,tr,ja,ko,zh-rCN`.
- `feature-graphic.png`: 1024 × 500, 24-Bit-PNG, nichts Wichtiges in den äußeren 10 %: ein einziges starkes Bild,
  die Stadt 2030 bei Nacht spät im Spiel (echte Karte), eng auf die Skyline mit Rechenzentren und leuchtenden
  Glasfaser-Leitungen rechts gerahmt; links geht der Nachthimmel in ein ruhiges Indigo über, darauf App-Icon, Name in
  Nunito Black und die Tagline in Glasfaser-Orange („Wire your town – from ISDN to fiber“).
  `feature-graphic/<sprache>.png`: dieselbe Grafik mit der Tagline der Sprache, für jede erzeugte Sprache; in der Play
  Console je Sprache hochladen.
- Vor dem Start (docs/RELEASE.md 4): die übrigen 10 Sprachen mit `-Pstore.locales=fr,es,it,pt-rBR,pl,nl,tr,ja,ko,zh-rCN`
  erzeugen und je Eintrag hochladen; sonst zeigt Play dort die Standardbilder mit deutscher bzw. englischer
  Beschriftung. Nicht eingecheckt, weil jeder Satz rund 8 MB PNG ist (zehn Sprachen ≈ 80 MB bei jedem Neuerzeugen).
- Eine Vorlage für alle Bilder: das Spiel randlos unter einer Überschriften-Leiste im Indigo der Marke (dieselbe Farbe wie Icon und Feature-Grafik; Spielbild ohne Farbfilter) mit dünner
  Glasfaser-Orange-Kante; die kurze Überschrift in Nunito Black, ein Schlüsselwort in der Akzentfarbe des Motivs und
  unterstrichen; nur Bild 1 trägt das App-Icon, Querformat-Bilder das Jahr der Szene rechts in einer Pille.
- Motive (Dateien `01-town` … `08-sceneries`), jedes in eigener Stimmung: 1 Held: Tag auf der Wiese, der Knoten einer
  späten Kleinstadt ist gerade zum Rechenzentrum gewachsen, alle Kabelarten laufen hinein, Kamera eng, sanfte Vignette;
  2 die Geste in Nahaufnahme auf der Insel in der Sonne (Sand, türkises Meer): ein Finger zieht die Glasfaser von der
  wartenden Konsole zum Spiele-Server, mit Preis-Blase; 3 Winter: ein Bagger hat im Bergdorf ein Kabel gekappt;
  4 Überlastung im echten Spielbild mit HUD, roter Rand; 5 Funk im warmen Wüsten-Thema: WLAN-, 5-GHz- und Mobilfunk-
  Zone, Server ganz im Bild; 6 großes Netz bei Nacht: die Stadt 2030 spät im Spiel mit Paketen auf jeder Leitung;
  7 die gedrehte Großstadt in der Abenddämmerung (Herbst-Thema unter tiefer Abendsonne) mit zwei Dreh-Pfeilen; 8 fünf
  Szenerien als Collage mit Namens-Pillen. Beschriftungen: `StoreScreenshotTest` (`LANGUAGES`, Schlüsselwörter `KEYWORDS`).
- `screenshots/phone-portrait/<sprache>/`: 1080 × 1920 für das Hochformat-Karussell von Play, alle 8 Motive
  (`01-town.png` … `08-sceneries.png`), jeweils für das Hochformat neu gerahmt: Szenen mit gewachsenem Land, damit das
  Bild bis zum Rand Karte zeigt und kein Nebel jenseits der offenen Fläche.
- Trailer: `trailer.md`.

Erzeugen:
`ROBOLECTRIC_DEPS_DIR=/opt/robolectric ./gradlew testDebugUnitTest --tests '*StoreScreenshotTest*'`
