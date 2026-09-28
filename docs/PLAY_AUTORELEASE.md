# Automatischer Play-Testrelease

Der Workflow `.github/workflows/play-release.yml` startet nach jedem Push auf den aktuellen Standardbranch
`claude/mini-networks-android-prototype-9l0v8n` (also auch nach einem Merge) und kann manuell gestartet werden.
Er prüft den Code, baut ein signiertes Android App Bundle und veröffentlicht denselben Versionscode
im internen Test und im bestehenden geschlossenen Track. Der Play API Edit wird erst nach beiden
Track-Updates committed. Die Produktion gehört bewusst nicht zum Workflow, solange der Zugang
zur Produktion im Play-Konto nicht freigeschaltet ist.

Einmalige Einrichtung für das GitHub-Environment `play-release`:

| Name | Art | Wert |
| --- | --- | --- |
| `PLAY_UPLOAD_KEYSTORE_BASE64` | Secret | Base64-kodierter Inhalt des bestehenden Upload-Keystores |
| `PLAY_UPLOAD_STORE_PASSWORD` | Secret | Keystore-Passwort |
| `PLAY_UPLOAD_KEY_ALIAS` | Secret | Alias des Upload-Schlüssels |
| `PLAY_UPLOAD_KEY_PASSWORD` | Secret | Passwort des Upload-Schlüssels |
| `PLAY_SERVICE_ACCOUNT_JSON` | Secret | JSON-Schlüssel eines Google-Cloud-Servicekontos mit Play-Release-Rechten nur für diese App |
| `PLAY_REVIEW_ACCESS_CODE` | Secret | Prüfercode für die kostenpflichtigen Inhalte im Test-Build; als Anleitung vertraulich in der Play Console hinterlegen |
| `PLAY_ADMOB_APP_ID` | Variable | Echte AdMob-App-ID |
| `PLAY_ADMOB_INTERSTITIAL_ID` | Variable | Echte Interstitial-Anzeigenblock-ID |
| `PLAY_ADMOB_REWARDED_ID` | Variable | Echte Rewarded-Anzeigenblock-ID |
| `PLAY_CLOSED_TRACK` | Variable | API-Kennung des bestehenden geschlossenen Tracks, standardmäßig `alpha` |

Das Servicekonto benötigt Zugriff auf die Google Play Developer API sowie die Berechtigung,
Test-Releases für `de.robinrehbein.mininetworks` zu verwalten. Vor dem ersten Merge einmal den
Workflow per `workflow_dispatch` testen und in der Play Console beide Versionscodes kontrollieren.
Fehlende Konfiguration beendet den Lauf vor dem Build. Jeder Lauf baut einen **Test-Build**
(`-Pmininetworks.channel=testing`) mit dem Versionscode `semantic × 1000 + (run_number mod 999)` und dem Namen
`0.9.2-test.<n>` (Schema in `docs/RELEASE.md` Abschnitt 1); `tools/publish_play.py` lädt nur hoch, wenn dieser Code über
allen Codes auf Play liegt.
Im Test-Build lässt sich unter Einstellungen → Prüferzugang (in allen 12 Sprachen übersetzt) mit dem Code der Zugriff
auf alle Premium-Szenerien und die werbefreie Variante aktivieren. Der Code ist kein Play-Kauf und darf nicht als
Kaufnachweis verwendet werden. Deshalb gilt: **Test-Builds nie in die Produktion hochstufen.** Das Produktions-Bundle
wird separat ohne Channel-Property gebaut (Versionscode endet auf 999); ist dabei `MININETWORKS_REVIEW_ACCESS_CODE`
gesetzt, bricht Gradle ab, und der Workflow prüft das bei jedem Lauf (Schritt „Production builds refuse the reviewer
code“).
Bei einer Änderung des GitHub-Standardbranches müssen die Branch-Filter in diesem Workflow und
in `ci.yml` angepasst werden.
