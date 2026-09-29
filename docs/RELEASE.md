# Mini Networks – Release

Schritt für Schritt vom Repo zum internen Test-Track im Play Store. Alles, was geheim ist (Upload-Schlüssel, Passwörter,
echte AdMob-IDs), liegt **nie** im Repo, sondern in Gradle-Properties (`~/.gradle/gradle.properties`, `-P…`) oder
Umgebungsvariablen (CI-Secrets). `.gitignore` schließt `*.jks`, `*.keystore` und `keystore.properties` aus.

## 1. Versionen

Ein einziges, monoton steigendes Schema für **jeden** Upload, lokal wie aus CI (`app/build.gradle.kts`). Play-Versionscodes
gelten global pro App, also müssen Test- und Produktions-Builds in dieselbe Reihenfolge passen:

- `versionName` steht in `app/build.gradle.kts` (`appVersionName`) als `MAJOR.MINOR.PATCH`, zurzeit `0.9.2`.
  MINOR und PATCH bleiben unter 100, sonst bricht der Build ab. `semantic = MAJOR × 10000 + MINOR × 100 + PATCH`
  (0.9.2 → 902, 1.0.0 → 10000).
- **Test-Build** (`-Pmininetworks.channel=testing -Pmininetworks.buildNumber=<n>`, n = 0…998; CI nimmt
  `run_number mod 999`): `versionCode = semantic × 1000 + n`, Name `0.9.2-test.<n>` (z. B. 902017).
- **Produktions-Build** (Standard, ohne diese Properties): `versionCode = semantic × 1000 + 999`, Name `0.9.2`
  (0.9.2 → 902999, 1.0.0 → 10000999). Er liegt über allen Test-Builds seiner Version und unter allen Test-Builds der
  nächsten. Die frühere CI-Reihe `100000 + run_number` liegt unter 902000, bleibt also darunter.
- Vor jeder neuen Produktionsversion PATCH (oder MINOR/MAJOR) erhöhen. `tools/publish_play.py` bricht ab, wenn ein
  Test-Code nicht über allen Codes auf Play liegt (nach 999 CI-Läufen mit demselben Namen): dann ebenfalls PATCH erhöhen.
- **Nie einen Test-Build in die Produktion hochstufen.** Test-Builds können den Prüferzugang enthalten
  (`MININETWORKS_REVIEW_ACCESS_CODE`, docs/PLAY_AUTORELEASE.md), der Premium-Inhalte ohne Play-Kauf freischaltet.
  Die Produktion bekommt immer ein eigenes `bundleRelease` ohne Channel-Property; ist dabei
  `MININETWORKS_REVIEW_ACCESS_CODE` gesetzt, bricht Gradle ab, ein Store-Bundle kann den Code also nie enthalten.
  Am Namen erkennbar: Test-Builds enden auf `-test.<n>`, ihr Code nicht auf 999.
- `./gradlew -q :app:printVersion` (mit denselben Properties) zeigt Code und Namen; Debug-Builds heißen `0.9.2-debug`.

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
- **Billing-Testmodus auf dem Gerät (E2, T7):** Im Code ist der Ablauf gegen ein nachgebautes Play-Konto getestet
  (`PurchasesTest` mit `FakeBillingGateway`: Kauf, Szenerie-Paket, ausstehende Zahlung, Bestätigen, Wiederherstellen
  nach Neuinstallation). Mit einem Lizenztester auf dem internen Test-Track zusätzlich einmal von Hand:
  1. „Werbefrei“ mit der Testkarte „Immer genehmigt“ kaufen → kein Interstitial mehr, „Weiterspielen“ ohne Video.
  2. „Alle Szenerien“ kaufen → Bergdorf und Zukunft 2030 spielbar.
  3. Einen Kauf mit „Langsame Testkarte, wird nach einigen Minuten genehmigt“ → bis zur Genehmigung nicht freigeschaltet
     und nicht erneut kaufbar, danach freigeschaltet (auch wenn die App zwischendurch geschlossen war).
  4. App deinstallieren und neu installieren → beim ersten Start ist alles wieder da (Abfrage `queryPurchasesAsync`).
  5. In der Play Console → Bestellungen: Die Käufe sind bestätigt (nicht nach 3 Tagen erstattet).

## 5. Play Console einrichten

1. **App anlegen:** Name „Mini Networks“ (bzw. der endgültige Name aus `app_name`), Standardsprache Deutsch (de-DE),
   Typ **Spiel**, **kostenlos**. Paketname `de.robinrehbein.mininetworks` (lässt sich später nicht ändern).
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
6. **Store-Eintrag:** Texte in 12 Sprachen, Screenshots, Feature-Grafik und Trailer-Storyboard in `docs/store/`
   (Übersicht `docs/store/README.md`; ältere Planung in `docs/store-listing.md`); Kategorie **Spiele → Strategie**
   (Alternative: Puzzle), Tags z. B. „Simulation“, „Casual“. Kontakt-E-Mail und Website eintragen.
7. **Datenschutzerklärung:** veröffentlicht unter https://robinrehbein.github.io/mini-networks/privacy/ (Deutsch und
   Englisch auf einer Seite; Quelle `docs/privacy-policy-web.md`, bauen mit `python3 tools/build_privacy_page.py`,
   ausführlich in `docs/privacy-policy.md`). Diese URL im Store-Eintrag **und** in der UMP-Nachricht eintragen. Kommt
   ein SDK dazu, beide Fassungen anpassen (`StoreListingTest.thePublishedPrivacyPageCoversEverySdkInGermanAndEnglish`).
8. **App-Inhalte** (Richtlinie → App-Inhalte): Datenschutzerklärung, Werbung („Ja, enthält Werbung“), App-Zugriff
   („Alle Funktionen ohne Anmeldung verfügbar“), Einstufung (Abschnitt 7), Zielgruppe (Abschnitt 8),
   Datensicherheit (Abschnitt 9), Behörden-App: Nein, Finanzfunktionen: Keine, Gesundheit: Nein, Nachrichten-App: Nein.

## 6. AdMob und UMP

1. In AdMob eine **Android-App** anlegen (zunächst „nicht im Store“, nach der Veröffentlichung mit dem Store-Eintrag
   verknüpfen). Die **App-ID** (`ca-app-pub-…~…`) → `mininetworks.admob.appId`.
2. Zwei Anzeigenblöcke: **Interstitial** → `mininetworks.admob.interstitialId`, **Rewarded** (Belohnung z. B. „1 ×
   Weiterspielen“, der Wert wird im Spiel nicht ausgewertet) → `mininetworks.admob.rewardedId`.
3. **Datenschutz & Mitteilungen:** eine **DSGVO-Nachricht** (EWR, UK, Schweiz) für die App erstellen und veröffentlichen,
   Datenschutz-URL eintragen, Sprachen: die 12 der App (docs/TOP100.md F1). Ohne veröffentlichte Nachricht zeigt UMP kein Formular und in der EU
   gibt es keine Werbung. Optional eine Nachricht für US-Bundesstaaten (Datenschutzgesetze der Staaten).
   Den Knopf „Datenschutz“ im Spiel (Einstellungen) zeigt die App nur, wenn UMP ihn verlangt.
4. **app-ads.txt:** die von AdMob angezeigte Zeile in `https://<entwickler-website>/app-ads.txt` ablegen; dieselbe
   Website als Entwickler-Website im Play Store eintragen.
5. Eigene Testgeräte in AdMob registrieren (Einstellungen → Testgeräte), damit echte Anzeigen im internen Test nicht
   angeklickt werden (Kontosperre wegen ungültiger Klicks).
6. Die neutrale Geburtsdatumsabfrage läuft vor UMP, Billing, Play Games und Mobile Ads. Für 13- bis 17-Jährige setzt
   die App Child- und Under-Age-of-Consent-Kennzeichnung sowie die Anzeigen-Einstufung G vor der Initialisierung
   des Werbe-SDKs. Die UMP-Anfrage ist ebenfalls als minderjährig gekennzeichnet. Google Play Games startet nur für
   Erwachsene.

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

In der Play Console sind **13–15, 16–17 und 18+** gewählt, nicht unter 13. Je nach Land zählen Jugendliche in
diesen Gruppen als Kinder. Die App fragt das Geburtsdatum neutral ab, startet vor der Antwort keine Werbe- oder
Spiele-SDKs, sperrt den Zugang unter 13 und behandelt 13- bis 17-Jährige bei AdMob konservativ als Kinder.
Vor einer Änderung der Zielgruppe oder der Werbe-SDKs die Familienrichtlinie erneut prüfen.

## 9. Datensicherheit (Data safety)

Grundlage: Was die App **selbst** verarbeitet, bleibt auf dem Gerät (Spielstand, Bestwerte, Statistiken, Einstellungen,
bekannte Käufe; keine eigenen Server, kein Analytics, kein Crash-Reporting). Zu erklären sind die SDKs, die im
Release-Build stecken (Stand T8, abgeglichen mit `app/build.gradle.kts` und dem gemergten Release-Manifest,
docs/TOP100.md F5):

| SDK (Version) | Wann aktiv | Daten an Google | Quelle zum Abgleich |
|---|---|---|---|
| Google Mobile Ads 25.2.0 + UMP 4.0.0 | kostenlose Version; nach „Werbefrei“ ab dem nächsten Start nicht mehr (`PlayRules.canInitializeAds`) | ja, siehe Tabelle | developers.google.com/admob/android/privacy/play-data-disclosure |
| Google Play Billing 9.1.0 | immer (Käufe, Wiederherstellen) | Kaufabwicklung durch Google Play | developer.android.com/google/play/billing (Data safety) |
| Play Games Services v2 22.1.0 | nur mit echten IDs in `games-ids.xml` und Play-Spiele-Profil (Abschnitt 11) | ja, siehe Tabelle | developer.android.com/games/pgs/data-collection |
| Play In-App Review 2.0.2 | höchstens alle 30 Tage nach einem guten Moment (`ReviewPolicy`) | nichts von der App; Google zeigt nur seinen Dialog, Bewertung geht direkt an Play | Google Play SDK Index (com.google.android.play:review) |
| AndroidX Core 1.15 (FileProvider), ProfileInstaller 1.4.1 | Teilen bzw. Installation | keine (lokal) | – |

**Allgemein**

| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App Nutzerdaten der erforderlichen Typen? | Ja |
| Werden alle Daten bei der Übertragung verschlüsselt? | Ja (die SDKs nutzen HTTPS) |
| Können Nutzer das Löschen ihrer Daten beantragen? | Ja, über Google: Werbe-ID in den Android-Einstellungen zurücksetzen/löschen, Play-Spiele-Daten über das Play-Spiele-Profil bzw. Google-Konto; der Spielstand verschwindet mit „App-Daten löschen“ bzw. der Deinstallation. Kontakt-E-Mail der Datenschutzerklärung für Fragen. |
| Unabhängige Sicherheitsprüfung (MASA) | Nein |

**Datentypen** (verarbeitet flüchtig: Nein)

| Datentyp | Durch | Erhoben / geteilt | Optional | Zweck |
|---|---|---|---|---|
| Standort → Ungefährer Standort (aus der IP-Adresse) | Mobile Ads | erhoben, geteilt | nein | Werbung oder Marketing, Analysen, Betrugsprävention/Sicherheit/Compliance |
| Geräte- oder andere IDs (Werbe-ID, App-Set-ID) | Mobile Ads | erhoben, geteilt | nein | Werbung oder Marketing, Analysen, Betrugsprävention/Sicherheit/Compliance |
| App-Aktivität → App-Interaktionen (Anzeigen gesehen/angetippt) | Mobile Ads | erhoben, geteilt | nein | Werbung oder Marketing, Analysen, Betrugsprävention/Sicherheit/Compliance |
| App-Informationen und Leistung → Absturzprotokolle, Diagnosen (des SDK) | Mobile Ads | erhoben, geteilt | nein | Analysen, Betrugsprävention/Sicherheit/Compliance |
| Persönliche Daten → Nutzer-IDs (Spieler-ID, Gamertag, Avatar) | Play Games Services | erhoben | ja (nur mit Play-Spiele-Profil) | App-Funktionalität, Kontoverwaltung |
| App-Aktivität → Sonstige Aktionen (freigeschaltete Erfolge, Bestenlisten-Punkte) | Play Games Services | erhoben | ja | App-Funktionalität |
| App-Aktivität → Sonstige nutzergenerierte Inhalte (Cloud-Spielstand „progress“: Statistiken, Bestwerte, Tagesserie) | Play Games Services | erhoben | ja | App-Funktionalität |

**Nicht erhoben:** Name, E-Mail, Kontakte, Fotos/Videos, Dateien, Nachrichten, Gesundheit, genauer Standort,
Finanzdaten. **Kaufverlauf:** Die App kennt nur die Produkt-IDs gekaufter Artikel und speichert sie lokal; die Zahlung
wickelt Google Play ab (keine Angabe als „erhoben“, solange nichts davon an uns oder Dritte geht – mit Googles aktueller
Billing-Anleitung abgleichen). **In-App-Review:** keine eigene Angabe; die App erfährt nicht, ob bewertet wurde.
**Teilen (D2):** Das Bild bleibt im App-Cache und geht nur über das System-Teilen-Menü an die gewählte App
(FileProvider, Lese-Berechtigung, keine Speicher-Berechtigung) – das ist eine Aktion des Nutzers, kein Erheben.
Play Games gilt als „erhoben“ (Daten gehen an Google als Anbieter des Dienstes), nicht als „geteilt“; vor dem
Absenden mit Googles Seite „Prepare for Google Play's data disclosure requirements“ (Play Games Services) abgleichen.
Solange `games-ids.xml` Platzhalter enthält, startet kein Play-Games-Code (Abschnitt 11) – dann entfallen die drei
Play-Games-Zeilen.

Mit „Werbefrei“ startet die App das Mobile Ads SDK nicht mehr (ab dem nächsten App-Start; `PlayRules.canInitializeAds`),
die Werbe-Zeilen gelten also für Spieler ohne diesen Kauf. Die lokal gemerkten Käufe (`monetization`-Einstellungen) sind
von Backup und Geräteumzug ausgenommen (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`): Besitz kommt immer
von Google Play.

Berechtigungen im gemergten Release-Manifest (`app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml`,
geprüft in T8): `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (SDKs),
`com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_AD_ID`/`_ATTRIBUTION`/`_TOPICS` (Mobile Ads, Privacy
Sandbox), `com.android.vending.BILLING` (Billing) und die interne Signatur-Berechtigung
`com.mininetworks.game.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX Core, nur für die eigene App) sowie
`VIBRATE` (eigene, für die Haptik-Einstellung; normale Berechtigung ohne Nachfrage). Keine
Standort-, Kontakt-, Kamera-, Mikrofon- oder Speicher-Berechtigung. Die Frage „Verwendet die App die Werbe-ID?“ ist
daher mit **Ja, Werbung oder Marketing** zu beantworten.

## 10. Checkliste vor dem ersten Upload

- [ ] `appVersionName`/`versionCode` erhöht
- [ ] Upload-Schlüssel gesetzt, `keytool -printcert` zeigt nicht „Android Debug“
- [ ] Ziel-API aktuell: Google Play verlangt seit 31. August 2026 für neue Apps und Updates **targetSdk 36**
      (`app/build.gradle.kts`); vor jedem Upload in der Play Console unter „Richtlinienstatus“ die aktuelle Frist prüfen.
      Android 16 ignoriert die feste Querlage auf großen Bildschirmen nur deshalb nicht, weil das Manifest
      `android:appCategory="game"` setzt; Hochformat-Fenster (Split-Screen, frei skalierbar) funktionieren trotzdem
      (`docs/screenshots/portrait-*.png`)
- [ ] Echte AdMob-IDs gesetzt: ein Release-Build warnt bei Google-Test-IDs und bricht ab, sobald der Upload-Schlüssel gesetzt ist
- [ ] Play-Games-IDs in `app/src/main/res/values/games-ids.xml` eingetragen (Abschnitt 11); sonst warnt der Release-Build und das Spiel läuft ohne Play Games
- [ ] `lintRelease` ohne Warnungen; bewusst ignorierte Prüfungen stehen mit Grund in `app/lint.xml`
- [ ] `./gradlew testDebugUnitTest assembleDebug lintDebug lintRelease bundleRelease` grün
- [ ] Baseline Profile im Bundle (`app/src/main/baseline-prof.txt`, docs/TOP100.md A4): `unzip -l app/build/outputs/bundle/release/app-release.aab | grep baseline.prof`;
      Inhalt prüfen mit `profgen dumpProfile -p <entpacktes assets/dexopt/baseline.prof> -a app/build/outputs/apk/release/app-release.apk -o dump.txt`
      (profgen liegt in `cmdline-tools/latest/bin`)
- [ ] Datenschutzerklärung veröffentlicht, URL in Play Console und UMP-Nachricht
- [ ] In-App-Produkte angelegt und aktiv, Lizenztester eingetragen
- [ ] Store-Eintrag in 12 Sprachen (`docs/store/<sprache>.md`), Icon 512 px (`docs/store/icon-512.png`), Feature-Grafik (je Sprache `docs/store/feature-graphic/<sprache>.png`; `docs/store/feature-graphic.png` ist die Standardgrafik mit englischer Tagline, nur Rückfall), Screenshots (`docs/store/screenshots/`)
- [ ] Store-Bilder und Feature-Grafiken der übrigen 10 Sprachen erzeugt und je Eintrag hochgeladen:
      `ROBOLECTRIC_DEPS_DIR=/opt/robolectric ./gradlew testDebugUnitTest --tests '*StoreScreenshotTest*' -Pstore.locales=fr,es,it,pt-rBR,pl,nl,tr,ja,ko,zh-rCN`
      (nicht eingecheckt, ≈ 80 MB PNG; ohne sie zeigt Play dort die deutschen bzw. englischen Bilder)
- [ ] Geräte-Checks aus Abschnitt 4 im internen Test bestanden

## 11. Play Games Services (Erfolge, Bestenlisten, Cloud-Spielstand)

Code: `app/src/main/java/com/mininetworks/game/games/` – Interface `GameServices`, Play-Umsetzung `PlayGameServices`
(Play Games Services v2), `NoOpGameServices` für Debug-Builds und Tests. Die Regeln liegen in `:core`
(`Leaderboards`, `AchievementSync`, `CloudProgress`) und sind per JVM-Test abgesichert.

**IDs (nur Platzhalter im Repo):** `app/src/main/res/values/games-ids.xml` enthält `app_id`, 39 `achievement_<id>` und
6 `leaderboard_<key>`, alle mit `TODO_`-Werten. Einrichten:

1. Play Console → „Play Games Services“ → Projekt anlegen und mit der App verknüpfen (OAuth-Client für den
   Upload- **und** den App-Signaturschlüssel, SHA-1 aus der Play Console).
2. 39 Erfolge anlegen, je einer pro Eintrag von `Achievements.all` (`core/.../Achievements.kt`; Titel/Beschreibung aus
   `achievement_*`-Texten in `strings.xml`); alle als normale (nicht inkrementelle) Erfolge – die App schaltet sie frei,
   sobald die lokalen Statistiken sie erreichen.
3. 6 Bestenlisten anlegen: je Szenerie (`scenery_river_town`, `scenery_metropolis`, `scenery_island_harbor`,
   `scenery_mountain_village`, `scenery_future_2030`; Wertung „höher ist besser“, Ganzzahl) und `daily` für die
   Tagesaufgabe (Punkte tragen das Tag `day<UTC-Tag>`; Hinweis: Play setzt die Tagesansicht um Mitternacht
   Pazifikzeit zurück, die Aufgabe wechselt um 0 Uhr UTC).
4. „Gespeicherte Spiele“ in der Konfiguration einschalten (ein Spielstand namens `progress`).
5. In `games-ids.xml` die `TODO_`-Werte durch die IDs aus „Ressourcen abrufen“ ersetzen; die **Namen** der Einträge
   bleiben, `app_id` ist die Projekt-ID (nur Ziffern). Nie IDs einer anderen App einchecken.
6. Tester in der Play Console eintragen, solange das Projekt nicht veröffentlicht ist.

Verhalten: Debug-Builds nutzen immer `NoOpGameServices` (außer mit `-Pmininetworks.playServicesInDebug=true`).
Release-Builds starten Play Games nur, wenn `app_id` eine Zahl ist (`GamesIds.configured`); der eigene Start-Provider
des SDK ist im Manifest entfernt, `MainActivity` ruft `PlayGamesSdk.initialize` selbst auf. Solange Platzhalter
drinstehen, warnt `bundleRelease`/`assembleRelease` („games-ids.xml still holds TODO_ placeholders“), baut aber.

- **Anmeldung:** still beim Start (Play Games v2 meldet automatisch an); „Bestenlisten“ im Hauptmenü meldet bei Bedarf an.
- **Erfolge (C2):** Bei jeder Anmeldung werden alle lokal erreichten Erfolge gesendet, danach jeder neu erreichte.
- **Bestenlisten (C3):** Game Over einer normalen Partie → Liste der Szenerie; Tagesaufgabe → `daily`, nur an ihrem UTC-Tag.
- **Cloud-Spielstand (C6):** Statistiken, Bestwerte, Tagesserie und Tagesbestwert (nicht die laufende Partie). Nach der
  Anmeldung wird er geladen und mit dem Gerät zusammengeführt (`CloudProgress.merge`: je Wert der höhere, Tagesserie
  und Tagesbestwert vom neueren Tag), bei jedem Game Over hochgeladen. Konflikte zweier Geräte löst die App selbst
  auf dieselbe Weise (`RESOLUTION_POLICY_MANUAL`); ein Stand einer neueren App-Version wird nie überschrieben.

**In-App-Review (D1):** `review/ReviewPrompt.kt` (`PlayReviewPrompt`, `NoOpReviewPrompt` in Debug-Builds), Regeln in
`:core` `ReviewPolicy`. Testen im internen Test-Track (Googles Dialog erscheint nur für Konten ohne Bewertung und
unterliegt einem Kontingent) oder mit `FakeReviewManager`.
