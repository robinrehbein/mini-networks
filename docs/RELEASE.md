# Mini Networks – Release

Schritt für Schritt vom Repo zum internen Test-Track im Play Store. Alles, was geheim ist (Upload-Schlüssel, Passwörter,
echte AdMob-IDs), liegt **nie** im Repo, sondern in Gradle-Properties (`~/.gradle/gradle.properties`, `-P…`) oder
Umgebungsvariablen (CI-Secrets). `.gitignore` schließt `*.jks`, `*.keystore` und `keystore.properties` aus.

## 1. Versionen

- `versionName` steht in `app/build.gradle.kts` (`appVersionName`) als `MAJOR.MINOR.PATCH`, zurzeit `0.9.0`
  (Release-Kandidat für den internen Test).
- `versionCode = MAJOR × 10000 + MINOR × 100 + PATCH` (0.9.0 → 900, 1.0.0 → 10000, 1.2.3 → 10203). MINOR und PATCH
  bleiben unter 100, sonst bricht der Build ab. Jede neue Version im Play Store braucht einen höheren Code, also vor
  jedem Upload mindestens PATCH erhöhen.
- Muss derselbe Name noch einmal hochgeladen werden (z. B. nur neu signiert), überschreibt CI den Code:
  `-Pmininetworks.versionCode=901`.
- Debug-Builds heißen `0.9.0-debug`.

## 2. Upload-Schlüssel anlegen (einmalig)

Play App Signing ist Pflicht für neue Apps: Google hält den eigentlichen App-Signaturschlüssel, wir signieren nur mit
einem **Upload-Schlüssel**. Geht er verloren, kann ihn der Konto-Inhaber in der Play Console zurücksetzen lassen.

```bash
mkdir -p ~/keys && keytool -genkeypair -v \
  -keystore ~/keys/mini-networks-upload.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Mini Networks, O=<Firma/Name>, C=DE"
```

Die Datei und die Passwörter gehören in einen Passwort-Manager (zwei Personen mit Zugriff), nicht ins Repo, nicht in
einen Chat. Bei PKCS12 sind Store- und Key-Passwort gleich.

## 3. Signieren beim Bauen

Der Release-Build liest vier Werte, jeweils als Gradle-Property **oder** Umgebungsvariable:

| Gradle-Property | Umgebungsvariable | Inhalt |
|---|---|---|
| `mininetworks.upload.storeFile` | `MININETWORKS_UPLOAD_STORE_FILE` | Pfad zur `.jks`-Datei |
| `mininetworks.upload.storePassword` | `MININETWORKS_UPLOAD_STORE_PASSWORD` | Store-Passwort |
| `mininetworks.upload.keyAlias` | `MININETWORKS_UPLOAD_KEY_ALIAS` | `upload` |
| `mininetworks.upload.keyPassword` | `MININETWORKS_UPLOAD_KEY_PASSWORD` | Key-Passwort |

Lokal am einfachsten in `~/.gradle/gradle.properties` (liegt außerhalb des Repos):

```properties
mininetworks.upload.storeFile=/home/<ich>/keys/mini-networks-upload.jks
mininetworks.upload.storePassword=…
mininetworks.upload.keyAlias=upload
mininetworks.upload.keyPassword=…
```

In CI: die `.jks` als Base64-Secret speichern, im Job nach `$RUNNER_TEMP/upload.jks` dekodieren und die vier
Umgebungsvariablen aus Secrets setzen.

**Fehlt auch nur ein Wert**, signiert Gradle den Release-Build mit dem Debug-Schlüssel und warnt:
„no upload key set … signed with the DEBUG key and cannot go to Play“. So laufen `assembleRelease` und `bundleRelease`
überall (auch in diesem Container), aber ein versehentlich debug-signiertes Bundle lehnt die Play Console ab.

## 4. Release bauen und prüfen

```bash
./gradlew testDebugUnitTest lintDebug lintRelease bundleRelease \
  -Pmininetworks.admob.appId=ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN \
  -Pmininetworks.admob.interstitialId=ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN \
  -Pmininetworks.admob.rewardedId=ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN
# Ergebnis: app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab   # muss den Upload-Schlüssel zeigen, nicht "CN=Android Debug"
```

- Der Release-Build läuft mit **R8** (Minify, `proguard-android-optimize.txt` + `app/proguard-rules.pro`) und
  **Ressourcen-Shrinking**. Die Regeln halten die Serializer der Spielstände (kotlinx.serialization) und die Billing-AIDL;
  Mobile Ads, UMP, Billing und kotlinx.serialization bringen eigene Consumer-Regeln mit.
- Die R8-Mapping-Datei (`app/build/outputs/mapping/release/mapping.txt`) steckt automatisch im AAB; die Play Console
  entschleiert damit Absturzberichte. Die Datei trotzdem je Version aufheben.
- Die echten AdMob-IDs können statt `-P` auch in `~/.gradle/gradle.properties` oder `local.properties` stehen
  (siehe `docs/PLAN.md`, „Bauen und Prüfen“). Ohne sie baut Release mit Googles **Test-IDs**: dann zeigt die App
  Testanzeigen – für den internen Test in Ordnung, für die Produktion nicht.
- **Vor jeder Veröffentlichung auf einem echten Gerät** (interner Test-Track) prüfen, was hier nicht prüfbar ist:
  minifizierter Build startet, Spielstand speichern → App beenden → Fortsetzen lädt ihn (Serializer nach R8),
  Einwilligungsformular erscheint (EWR-Gerät oder UMP-Debug-Geografie), Test-Interstitial nach der 4. Partie,
  Rewarded „Weiterspielen“, Testkauf „Werbefrei“ mit einem Lizenztester, 60 fps in einer vollen Partie (offen aus P4.2).

## 5. Play Console einrichten

1. **App anlegen:** Name „Mini Networks“ (bzw. der endgültige Name aus `app_name`), Standardsprache Deutsch (de-DE),
   Typ **Spiel**, **kostenlos**. Paketname `com.mininetworks.game` (lässt sich später nicht ändern).
2. **Play App Signing** annehmen (Standard) und beim ersten Upload das mit dem Upload-Schlüssel signierte AAB hochladen.
3. **Interner Test:** Track „Interner Test“, Testerliste (E-Mail-Adressen des Teams), AAB hochladen, Versionshinweise
   (DE + EN), veröffentlichen, Opt-in-Link an die Tester.
4. **Lizenztester** (Einstellungen → Lizenztests): dieselben Konten, damit Käufe im Test nichts kosten.
5. **In-App-Produkte** (Monetarisieren → Produkte → In-App-Produkte), alle **einmalig, nicht verbrauchbar**; die IDs
   stehen fest im Code (`Entitlements`) und müssen exakt so heißen:

   | Produkt-ID | Vorschlag Name (DE / EN) | Vorschlag Preis |
   |---|---|---|
   | `remove_ads` | Werbefrei / Remove ads | 2,99 € |
   | `scenery_metropolis` | Szenerie Großstadt / Metropolis scenery | 1,99 € |
   | `scenery_island_harbor` | Szenerie Insel & Hafen / Island & Harbour scenery | 1,99 € |
   | `scenery_mountain_village` | Szenerie Bergdorf / Mountain Village scenery | 1,99 € |
   | `scenery_future_2030` | Szenerie Zukunft 2030 / Future 2030 scenery | 1,99 € |
   | `scenery_pack` | Alle Szenerien / All sceneries | 4,99 € |

   Produkte lassen sich erst anlegen, wenn ein AAB mit der Billing-Berechtigung hochgeladen ist (Schritt 3).
   Die Preise zeigt die App so an, wie Play sie liefert; im Code steht kein Preis.
6. **Store-Eintrag:** Texte, Grafiken und Screenshot-Plan in `docs/store-listing.md`; Kategorie **Spiele → Strategie**
   (Alternative: Puzzle), Tags z. B. „Simulation“, „Casual“. Kontakt-E-Mail und Website eintragen.
7. **Datenschutzerklärung:** `docs/privacy-policy.md` (DE/EN) mit echtem Verantwortlichen füllen, auf einer öffentlichen
   Seite (Website, GitHub Pages) veröffentlichen und die URL im Store-Eintrag **und** in der UMP-Nachricht eintragen.
8. **App-Inhalte** (Richtlinie → App-Inhalte): Datenschutzerklärung, Werbung („Ja, enthält Werbung“), App-Zugriff
   („Alle Funktionen ohne Anmeldung verfügbar“), Einstufung (Abschnitt 7), Zielgruppe (Abschnitt 8),
   Datensicherheit (Abschnitt 9), Behörden-App: Nein, Finanzfunktionen: Keine, Gesundheit: Nein, Nachrichten-App: Nein.

## 6. AdMob und UMP

1. In AdMob eine **Android-App** anlegen (zunächst „nicht im Store“, nach der Veröffentlichung mit dem Store-Eintrag
   verknüpfen). Die **App-ID** (`ca-app-pub-…~…`) → `mininetworks.admob.appId`.
2. Zwei Anzeigenblöcke: **Interstitial** → `mininetworks.admob.interstitialId`, **Rewarded** (Belohnung z. B. „1 ×
   Weiterspielen“, der Wert wird im Spiel nicht ausgewertet) → `mininetworks.admob.rewardedId`.
3. **Datenschutz & Mitteilungen:** eine **DSGVO-Nachricht** (EWR, UK, Schweiz) für die App erstellen und veröffentlichen,
   Datenschutz-URL eintragen, Sprachen DE + EN. Ohne veröffentlichte Nachricht zeigt UMP kein Formular und in der EU
   gibt es keine Werbung. Optional eine Nachricht für US-Bundesstaaten (Datenschutzgesetze der Staaten).
   Den Knopf „Datenschutz-Einstellungen“ im Spiel (Einstellungen) zeigt die App nur, wenn UMP ihn verlangt.
4. **app-ads.txt:** die von AdMob angezeigte Zeile in `https://<entwickler-website>/app-ads.txt` ablegen; dieselbe
   Website als Entwickler-Website im Play Store eintragen.
5. Eigene Testgeräte in AdMob registrieren (Einstellungen → Testgeräte), damit echte Anzeigen im internen Test nicht
   angeklickt werden (Kontosperre wegen ungültiger Klicks).
6. Die App sagt nichts zur Zielgruppe (kein `tagForChildDirectedTreatment`); das passt nur, solange die Zielgruppe
   ab 13 Jahren ist (Abschnitt 8).

## 7. Inhaltseinstufung (IARC-Fragebogen)

Kategorie **Spiel**. Antworten nach dem Stand des Spiels:

| Frage | Antwort |
|---|---|
| Gewalt, Blut, Angst, sexuelle Inhalte, Nacktheit, Vulgärsprache | Nein |
| Drogen, Alkohol, Tabak | Nein |
| Glücksspiel oder simuliertes Glücksspiel, Lootboxen | Nein (Käufe sind feste Inhalte, kein Zufall) |
| Nutzer können interagieren / Inhalte teilen / chatten | Nein |
| Teilt den Standort des Nutzers | Nein |
| Digitale Käufe | **Ja** (In-App-Käufe) |
| Werbung | **Ja** |
| Kontroverse Themen, Hassrede | Nein |

Erwartetes Ergebnis: USK 0 / PEGI 3 / ESRB Everyone (mit Hinweisen „In-Game Purchases“ / „In-App-Käufe“).

## 8. Zielgruppe

Empfehlung: **13–15, 16–17 und 18+** ankreuzen, **nicht** unter 13, und „App ist nicht speziell auf Kinder
ausgerichtet“. Grund: Mit Kindern als Zielgruppe gilt die Familienrichtlinie (nur „Families“-zertifizierte
Werbenetzwerke, Kennzeichnung kindgerechter Anfragen, keine personalisierte Werbung) – das ist im Code nicht umgesetzt.
Das Spiel darf in Grafik und Store-Texten nicht gezielt Kinder ansprechen (tut es nicht).

## 9. Datensicherheit (Data safety)

Grundlage: Was die App **selbst** verarbeitet, bleibt auf dem Gerät (Spielstand, Bestwerte, Einstellungen, bekannte
Käufe; keine eigenen Server, kein Analytics, kein Crash-Reporting). Zu erklären sind die SDKs: **Google Mobile Ads**
(mit UMP) und **Google Play Billing**. Die Antworten folgen Googles Hinweisen zum Mobile Ads SDK; vor dem Absenden mit
der aktuellen Fassung von „Google Mobile Ads SDK – Datensicherheit“ und „Play Billing – Datensicherheit“ abgleichen,
die Google für seine SDKs pflegt.

**Allgemein**

| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App Nutzerdaten der erforderlichen Typen? | Ja |
| Werden alle Daten bei der Übertragung verschlüsselt? | Ja (die SDKs nutzen HTTPS) |
| Können Nutzer das Löschen ihrer Daten beantragen? | Nein – wir halten keine Daten; Werbe-ID zurücksetzen/löschen geht in den Android-Einstellungen, der Spielstand verschwindet mit „App-Daten löschen“ bzw. der Deinstallation. (Falls die Console einen Weg verlangt: Kontakt-E-Mail der Datenschutzerklärung.) |
| Unabhängige Sicherheitsprüfung (MASA) | Nein |

**Datentypen** (alle: *erhoben* und *geteilt* durch das Mobile Ads SDK an Google; nicht optional, außer wo vermerkt;
verarbeitet flüchtig: Nein)

| Datentyp | Zweck |
|---|---|
| Standort → Ungefährer Standort (aus der IP-Adresse) | Werbung oder Marketing, Analysen, Betrugsprävention/Sicherheit/Compliance |
| Geräte- oder andere IDs (Werbe-ID, App-Set-ID) | Werbung oder Marketing, Analysen, Betrugsprävention/Sicherheit/Compliance |
| App-Aktivität → App-Interaktionen (Anzeigen gesehen/angetippt) | Werbung oder Marketing, Analysen, Betrugsprävention/Sicherheit/Compliance |
| App-Informationen und Leistung → Absturzprotokolle, Diagnosen (des SDK) | Analysen, Betrugsprävention/Sicherheit/Compliance |

**Nicht erhoben:** Name, E-Mail, Konten, Kontakte, Fotos, Dateien, Nachrichten, Gesundheit, genauer Standort,
Finanzdaten. **Kaufverlauf:** Die App kennt nur die Produkt-IDs gekaufter Artikel und speichert sie lokal; die
Zahlung wickelt Google Play ab (keine Angabe als „erhoben“, solange nichts davon an uns oder Dritte geht – mit Googles
aktueller Billing-Anleitung abgleichen).

Mit „Werbefrei“ startet die App das Mobile Ads SDK nicht mehr (ab dem nächsten App-Start; `PlayRules.canInitializeAds`),
die Angaben oben gelten also für Spieler ohne diesen Kauf. Die lokal gemerkten Käufe (`monetization`-Einstellungen) sind
von Backup und Geräteumzug ausgenommen (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`): Besitz kommt immer
von Google Play.

Berechtigungen im Release-Manifest (aus den SDKs): `INTERNET`, `ACCESS_NETWORK_STATE`, `com.google.android.gms.permission.AD_ID`,
`ACCESS_ADSERVICES_AD_ID`/`_ATTRIBUTION`/`_TOPICS` (Privacy Sandbox), `com.android.vending.BILLING`, `WAKE_LOCK`,
`FOREGROUND_SERVICE`. Die Frage „Verwendet die App die Werbe-ID?“ ist daher mit **Ja, Werbung oder Marketing** zu beantworten.

## 10. Checkliste vor dem ersten Upload

- [ ] `appVersionName`/`versionCode` erhöht
- [ ] Upload-Schlüssel gesetzt, `keytool -printcert` zeigt nicht „Android Debug“
- [ ] Ziel-API aktuell: Google Play verlangt seit 31. August 2026 für neue Apps und Updates **targetSdk 36**
      (`app/build.gradle.kts`); vor jedem Upload in der Play Console unter „Richtlinienstatus“ die aktuelle Frist prüfen.
      Android 16 ignoriert die feste Querlage auf großen Bildschirmen nur deshalb nicht, weil das Manifest
      `android:appCategory="game"` setzt; Hochformat-Fenster (Split-Screen, frei skalierbar) funktionieren trotzdem
      (`docs/screenshots/portrait-*.png`)
- [ ] Echte AdMob-IDs gesetzt: ein Release-Build warnt bei Google-Test-IDs und bricht ab, sobald der Upload-Schlüssel gesetzt ist
- [ ] `lintRelease` ohne Warnungen; bewusst ignorierte Prüfungen stehen mit Grund in `app/lint.xml`
- [ ] `./gradlew testDebugUnitTest assembleDebug lintDebug lintRelease bundleRelease` grün
- [ ] Datenschutzerklärung veröffentlicht, URL in Play Console und UMP-Nachricht
- [ ] In-App-Produkte angelegt und aktiv, Lizenztester eingetragen
- [ ] Store-Eintrag DE + EN, Icon 512 px (`docs/screenshots/store-icon-512.png`), Feature-Grafik, Screenshots
- [ ] Geräte-Checks aus Abschnitt 4 im internen Test bestanden
