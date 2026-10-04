# Spieltest mit echten Testern (B4)

Ersetzt die KI-Jury aus `docs/TOP100.md` B4 (Entscheidung des Gründers, 2026-09-28). Der Zielwert bleibt unverändert: **Ø ≥ 8/10**.

## Ablauf
1. Build über `.github/workflows/play-release.yml` in den internen Test-Track von Google Play laden (siehe `docs/PLAY_AUTORELEASE.md`).
2. 5–10 Tester einladen, die das Spiel vorher nicht kannten. Wer mitentwickelt hat, zählt nicht.
3. Jede Person spielt mindestens 3 Partien an mindestens 2 verschiedenen Tagen. Dazu gehören das Tutorial und die erste Szenerie. Anleitung darüber hinaus gibt es keine.
4. Danach füllt jede Person den Fragebogen unten aus, ohne die Antworten der anderen zu sehen.

## Fragebogen (je 1–10)
| Nr. | Frage | Rubrik |
|---|---|---|
| 1 | Wie gut konntest du erkennen, was auf der Karte passiert (Geräte, Kabel, Überlast)? | Lesbarkeit |
| 2 | Wie stimmig wirken Grafik, Menüs und Store-Bilder zusammen? | Stimmigkeit |
| 3 | Wie stark wolltest du nach einem Game Over direkt noch eine Partie spielen? | Reiz zum Weiterspielen |

Freitext: Was hat dich am meisten gestört? Was hat dir am besten gefallen?

## Zusatzfragen (zählen nicht zur B4-Wertung)
| Nr. | Frage | Antwort |
|---|---|---|
| 4 | Wie schwer fühlte sich das Spiel an? (1 = viel zu leicht, 5–6 = genau richtig, 10 = viel zu schwer) | 1–10, **nicht Teil der Wertung** |
| 5 | Welche Woche hast du in deiner besten Partie erreicht? (steht auf der Game-Over-Karte) | Zahl |
| 6 | Woran bist du gescheitert, und hat der Tipp auf der Game-Over-Karte geholfen? | Freitext |
| 7 | Welche Spielmechanik hast du erst spät oder gar nicht verstanden? (z. B. Server aufrüsten, Kabel aufrüsten oder umlegen, Stau, Störfälle, WLAN/Mast, Pause und weiterbauen) | Freitext |

## Auswertung
- Wertung je Person = Mittel der drei Fragen. **Ergebnis = Mittel aller Personen**, auf zwei Stellen gerundet.
- B4 gilt als erreicht bei Ergebnis ≥ 8,00 mit mindestens 5 gültigen Bögen.
- Ergebnis, Einzelwertungen, Zahl der Tester und Build-Version trägt man in `docs/TOP100.md` ein (B4 und Abschnitt 4).
- Nennen mindestens 3 Personen dasselbe Problem, wird es vor dem Soft Launch behoben.
- Neben der B4-Wertung werden berichtet: der **Median der erreichten Woche** (Frage 5) und die **Verteilung der Schwierigkeit** (Frage 4: Mittel, Minimum, Maximum). Liegt das Mittel der Schwierigkeit außerhalb von 4–7, folgt eine Balancing-Runde (`docs/BALANCING.md`).
- Die Antworten auf Frage 6 und 7 zeigen, welche Tipps (Game-Over-Tipp, einmalige Tipps, Legende über „?“) noch fehlen oder nicht ankommen.
