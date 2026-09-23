# Meilenstein 2 – Zuschnitt und Graustufen

Verlustfreier Auto-Zuschnitt nach `_input/reference/autocrop_reference.py` (`jpegtran -crop`), Graustufen (`jpegtran -grayscale`), Befehl `crop IN OUT`, `scan` speichert die verarbeitete Seite (mit `--keep-raw` zusätzlich das Roh-JPEG), Tests mit echten und synthetischen Bildern.

**Anforderungen:** SV-01, SV-02, SV-03, SV-06, SV-07, TE-02, BE-02, BE-03.

**Ausdrücklich nicht Teil dieses Meilensteins:** SV-04 (`normalize`, offen – OF-09), SV-05 (Seitengröße, folgt in Meilenstein 3).

**Status:** in Arbeit – Aufgabenliste vorgelegt, noch kein Code gebaut.

## Festgehaltene Entscheidungen dieser Liste

Diese Punkte wurden vor dem Bau mit dem Auftraggeber geklärt und gelten für den ganzen Meilenstein:

1. **Schichtung:** Die Verarbeitung ist eine Schritt-Kette (`processing`), die Ablage ist Sache des Aufrufers (`cli`, später der Dienst). Damit kommen Drehen, Geraderücken und PDF-Bau später als weitere Schritte bzw. Ablagen dazu, ohne die Kette umzubauen (SV-07). `keep-raw` ist ein reines Ablage-Thema und sitzt **nicht** in der Kette.
2. **`crop IN OUT` schneidet nur zu** – keine Farbänderung. Graustufen veranlasst `scan` (`--color-mode`, Default `gray`) bzw. später der Dienst. Deshalb ist der A4-Abnahmepunkt am `crop`-Befehl wörtlich erfüllbar: kein Zuschnitt nötig → gar kein `jpegtran`-Lauf → Ausgabe bytegleich zur Eingabe.
3. **`scan` schreibt ohne Flag nur die verarbeitete Seite.** `--keep-raw` legt das Roh-JPEG zusätzlich ab (SV-06). Default `keep-raw=false` gilt überall gleich (in Meilenstein 3 über die Property `unboundair.keep-raw`).
4. **Synthetische Testbilder werden als Dateien committet** (TE-02 wörtlich: „liegen als Test-Ressourcen vor"). Erzeugt im Dev Container per ImageIO; Parameter stehen unten bei T2.8/T2.9.
5. **Der Dev Container läuft lokal.** Der alte Umweg über einen anderen Rechner ist weg; der lokale Ablauf steht im Teststand und wird in `entwicklung.md` nachgezogen (T2.3). Deshalb auch T2.2: die „Aktuelle Lage" in `plan.md` ist veraltet.

## Testdefinitionen

Wie die zentralen Abnahmepunkte konkret gemessen werden – vorab festgelegt, damit es beim Testen nichts zu interpretieren gibt:

- **Kuvert (SV-01):** `envelope_dl_300dpi_raw.jpg` (roh 1776×2769) wird auf **exakt 1216×2494 px** mit Ursprung **+560+56** zugeschnitten (iMCU 16×8, nach innen gerundet; entspricht 103,0 × 211,2 mm). Die Luma-Werte der Ausgabe sind **exakt** identisch mit dem Original-Ausschnitt – verglichen über denselben Dekodierpfad (ImageIO → ganzzahliges BT.601-Luma). Vorab per Nachbau des Algorithmus in `jshell` im Dev Container verifiziert.
- **A4 (SV-01):** `din_a4_300dpi_raw.jpg` hat keine schwarzen Zeilen/Spalten; die Papier-Bbox ist das volle Bild (wird schon in Testpunkt 1 geprüft). Der Zuschnitt-Schritt reicht die Datei dann unverändert durch; am `crop`-Befehl ist die Ausgabe **bytegleich** zur Eingabe. Mit Graustufen (Default von `scan`) ist die Ausgabe bewusst **nicht** bytegleich – es läuft `jpegtran -grayscale`, das weiterhin nicht neu komprimiert, aber die Farbkanäle entfernt.
- **Graustufen-Luma (SV-03):** `gray` ergibt genau eine Komponente; ihre Werte werden gegen die Luma des Originals verglichen. Beim Zuschnitt ist Identität exakt (Koeffizienten werden kopiert). Beim Graustufen-Ergebnis wird zuerst gemessen, ob die Abweichung exakt 0 ist; falls der RGB-Rückweg des Originals ±1 verursacht, wird die Assertion auf „maximale Abweichung 1, Mittelwert < 0,01" gesetzt und begründet – **nie** stillschweigend gelockert.
- **SV-02-Schwellen:** `minPaperAreaFraction = 0.10` (Beispielwert aus SV-02), `maxAspectRatio = 6.0` (gewählt; DL-Kuvert ≈ 2,0, A4 ≈ 1,41). Beide sind benannte, einstellbare Konstanten in `PageSettings` und werden als reine Funktion getestet – dafür braucht es kein weiteres Testbild.
- **iMCU aus der Datei (OF-06):** Die iMCU-Größe wird aus den Sampling-Faktoren des JPEG selbst gelesen (ImageIO-Metadaten, `javax_imageio_jpeg_image_1.0`, Attribute `HsamplingFactor`/`VsamplingFactor`), nie angenommen. Die echten Bilder haben 4:2:2 → 16×8; die synthetischen weichen davon ab und belegen, dass der Wert tatsächlich gelesen wird.

## Pakete und Umsetzung

Wie diese Aufgabenliste abgearbeitet wird (festgelegt vor dem ersten Lauf, nach Absprache mit dem Auftraggeber):

- **Granularität:** Ein Agent bekommt grundsätzlich **eine Datei pro Lauf** – Implementation und Test sind getrennte Läufe. Gruppiert sind nur kleine Doku-/Fixture-Pakete mit höchstens zwei Dateien (P1–P3, P5). Größenregel: Pakete mit mehr als zwei Dateien oder mehr als ~250 neuen Zeilen werden von vornherein in Ein-Datei-Läufe zerlegt.
- **Reihenfolge:** Die Läufe folgen der Aufgabenreihenfolge; die Test-Datei kommt direkt nach der Datei, die sie testet. Nach jedem Testpunkt läuft der volle `./gradlew build` im Dev Container.
- **Schnittstellen-Übergabe:** Damit der Test-Lauf nicht raten muss, meldet jeder Lauf seine öffentliche API in höchstens zehn Zeilen; die Übergabe geht wörtlich an den nächsten Lauf.
- **Eskalation je Lauf:** `local-primary` → `local-second-opinion` → `local-third-opinion` → `general` (Session-Modell). Streng hintereinander, nie zwei gleichzeitig; `cloud-fallback` bleibt deaktiviert.
- **Erkennen „Paket zu groß":** Starke Signale (sofort auf Ein-Datei-Plan für alle Restläufe umschalten): Eskalation nötig, Agent fasst Dateien außerhalb seines Auftrags an, Abnahmekriterien bleiben unerfüllt. Schwache Signale (nach zwei Vorkommen umschalten): mehr als eine Korrekturrunde, Compile-Fehler aus nicht zusammenpassenden Schnittstellen, Verifizierung übersprungen.
- **Verifizierung je Lauf:** `src` per `docker cp` in den Dev Container spiegeln, `./gradlew spotlessApply test --tests '<Klasse>'` ausführen, formatierte Quellen zurückkopieren.
- **Commits:** macht der Hauptlauf pro grünem Lauf (Conventional Commits, englisch); gepusht wird nach jedem bestandenen Testpunkt.

## Teststand

Der Dev Container wird lokal betrieben: der Wrapper `unboundair-devcontainer` (liegt außerhalb des Repos, `--fresh` legt Container und Repo-Volume neu an, das Gradle-Cache-Volume bleibt) startet bzw. erneuert den Container; danach wird das Repo per `docker cp` gespiegelt, der Stand per `git status` im Container gegengeprüft und mit `./gradlew build` getestet. Details zieht T2.3 nach `entwicklung.md`.

| Was | Stand |
|---|---|
| Zuletzt getesteter Commit | `6fd6221` (Testpunkt 1) |
| Ergebnis | Testpunkt 1 bestanden – `./gradlew clean build` grün, 26 Tests ohne Fehler (`JpegInfoTest` 5, `PaperDetectorTest` 9, dazu die 12 aus Meilenstein 1), Linter sauber |
| Als Nächstes zu prüfen | Testpunkt 2, nach dem Bau der Aufgaben T2.16–T2.21 |
| Fixtures, bytegleich (T2.6/T2.7) | `envelope_dl_300dpi_raw.jpg` sha256 `f87c6028ccb63987127b33b905803c64123a51ce8364864c14336ef05fceaf34` · `din_a4_300dpi_raw.jpg` sha256 `78477b4f58d78e442e240ef1a801e420027adcf5dfbac288dded8c76651204e2` |

## Testpunkte

Jeder Testpunkt schließt eine Gruppe von Aufgaben ab; ein Fehlschlag bleibt klein und zuordenbar.

| # | Prüft | Aufgaben | Anforderungen | Ergebnis |
|---|---|---|---|---|
| 0 | Basislauf im frisch angelegten Dev Container: `clean build` auf dem Stand von Meilenstein 1 | – | – | **bestanden** (`1c57ce2`) |
| 1 | Testbilder als Ressourcen; Maße und iMCU werden aus der Datei gelesen; Papier-Bbox an echten und synthetischen Bildern | T2.6–T2.15 | TE-02, SV-01, SV-02 | **bestanden** (`6fd6221`) |
| 2 | `jpegtran` läuft als externes Programm; Zuschnitt: Kuvert exakt 1216×2494 mit Luma-Identität, A4 unverändert (bytegleich), dunkles Bild unbeschnitten mit Warnung, Streifen unten abgeschnitten | T2.16–T2.21 | SV-01, SV-02 | *offen* |
| 3 | Graustufen (eine Komponente, Luma unverändert); Schritt-Kette: Dummy-Schritt einhängbar, Reihenfolge, Aufräumen | T2.22–T2.25 | SV-03, SV-07 | *offen* |
| 4 | Befehle: `scan` gegen den Fake-Scanner (nur verarbeitete Seite, mit `--keep-raw` zusätzlich roh), `crop IN OUT` ergibt dasselbe wie SV-01 | T2.26–T2.30 | BE-02, BE-03, SV-06, DC-03 | *offen* |

## Aufgaben

Je Aufgabe: eine ID, genau eine Datei, ein prüfbares Abnahmekriterium und die Anforderungs-IDs, die sie umsetzt. Ein Haken bedeutet: gebaut **und** abgenommen; geschrieben allein genügt nicht.

### Ohne Testlauf

- [ ] **T2.0** `docs/meilenstein-2.md` – diese Aufgabenliste. *Abnahme:* Enthält die Aufgaben T2.0–T2.32 je mit ID, genau einer Datei, einem prüfbaren Abnahmekriterium und den Anforderungs-IDs; Testpunkte und Teststand vorhanden. *Anforderung:* Planpflege nach `AGENTS.md` (kein ID-Bereich betroffen).
- [ ] **T2.1** `docs/plan.md` – Meilenstein-2-Verweis und „Aktuell". *Abnahme:* Die Meilenstein-Liste verweist auf `meilenstein-2.md`; „Aktuell" nennt Meilenstein 2 als in Arbeit. *Anforderung:* Planpflege.
- [ ] **T2.2** `docs/plan.md` – „Aktuelle Lage" (Absatz über die fehlende Container-Runtime) korrigieren. *Abnahme:* Der Absatz beschreibt, dass der Dev Container lokal läuft und Tests lokal ausgeführt werden; kein Verweis auf einen anderen Rechner. **Wird vor dem Commit vorgezeigt.** *Anforderung:* Planpflege.
- [ ] **T2.3** `docs/entwicklung.md` – Abschnitt „Wie getestet wird, solange keine Runtime da ist" und „Zusammenarbeit am Repository" auf den lokalen Ablauf umstellen (Wrapper `unboundair-devcontainer` mit `--fresh`, Spiegelung per `docker cp`, Gegenprüfung per `git status` im Container, `./gradlew build`). *Abnahme:* Wer nur die Datei liest, kann den lokalen Testlauf selbst nachvollziehen; der Umweg-Abschnitt ist ersetzt. **Wird vor dem Commit vorgezeigt.** *Anforderung:* DO-04.
- [ ] **T2.4** `docs/offene-fragen.md` – OF-06 fortschreiben. *Abnahme:* „So gebaut" bestätigt, dass die iMCU-Größe aus dem JPEG gelesen wird (Sampling-Faktoren aus ImageIO-Metadaten); zusätzlich die [Analyse]-Beobachtung, dass die Bildanalyse das Bild vollständig in den Arbeitsspeicher dekodiert (bei 600 dpi ~300 MB Spitze, Default-Heap im Dev Container 1,19 GB). *Anforderung:* DO-07.
- [ ] **T2.5** `README.md` – Stand und Wegweiser. *Abnahme:* „Meilenstein 2 von 5" als Aufbaustand; der Wegweiser verlinkt `meilenstein-2.md` (weiterhin auch `meilenstein-1.md` als Historie); kein Schnellstart. *Anforderung:* DO-08.
- [ ] **T2.32** `.opencode/README.md` – Eskalations-Absatz um die vierte Stufe ergänzen. *Abnahme:* Der Abschnitt „Eskalation" nennt nach den drei lokalen Modellen den eingebauten `general`-Agenten (Session-Modell) als vierte Stufe; `cloud-fallback` bleibt deaktiviert. *Anforderung:* Planpflege nach `AGENTS.md` (kein ID-Bereich betroffen). *(Nachträglich ergänzt, zusammen mit dem Abschnitt „Pakete und Umsetzung" – festgelegt in der Absprache zur Umsetzung.)*

### Testpunkt 1 – Testbilder und Bildanalyse

- [ ] **T2.6** `src/test/resources/fixtures/envelope_dl_300dpi_raw.jpg` – echtes Kuvert-Testbild, bytegleich aus `_input/fixtures/` übernommen. *Abnahme:* `sha256sum` identisch mit der Quelle (Wert wird im Teststand notiert). *Anforderung:* TE-02.
- [ ] **T2.7** `src/test/resources/fixtures/din_a4_300dpi_raw.jpg` – echtes A4-Testbild, bytegleich aus `_input/fixtures/` übernommen. *Abnahme:* `sha256sum` identisch mit der Quelle (Wert wird im Teststand notiert). *Anforderung:* TE-02.
- [ ] **T2.8** `src/test/resources/fixtures/a4_bottom_stripe.jpg` – synthetisch: helle A4-Fläche ohne Schwarz oben und seitlich, dunkler Streifen nur unten (erzeugt per ImageIO: 1240×1754 px, Streifen 200 px, weißer Rand oben/seitlich). *Abnahme:* Wird in `JpegInfoTest` und `CropStepTest` genutzt; die Papier-Bbox reicht bis an den Streifen; Erzeugungsparameter sind in dieser Aufgabe dokumentiert. *Anforderung:* TE-02.
- [ ] **T2.9** `src/test/resources/fixtures/dark_page.jpg` – synthetisch: vollständig dunkles Bild (erzeugt per ImageIO, Luma ≈ 5). *Abnahme:* `PaperDetector` findet kein Papier; der SV-02-Pfad (unbeschnitten + Warnung) ist damit am echten Dateiweg belegt. *Anforderung:* TE-02, SV-02.
- [ ] **T2.10** `src/test/kotlin/dev/digiwomb/unboundair/TestImages.kt` – Test-Ressourcen als temporäre Dateien bereitstellen. *Abnahme:* Die Tests laden alle vier Fixtures als `Path`; eine Test-Ressource, die es nicht gibt, schlägt mit klarer Meldung fehl. *Anforderung:* TE-02.
- [ ] **T2.11** `src/main/kotlin/dev/digiwomb/unboundair/image/JpegInfo.kt` – Maße, Komponentenzahl und Sampling-Faktoren → iMCU-Größe, gelesen aus dem JPEG selbst (ImageIO-Metadaten). *Abnahme:* `JpegInfoTest` für echte und synthetische Bilder. *Anforderung:* SV-01, OF-06.
- [ ] **T2.12** `src/main/kotlin/dev/digiwomb/unboundair/image/LumaImage.kt` – JPEG per ImageIO dekodieren, Luma ganzzahlig nach BT.601 (`(299R+587G+114B)/1000`) als kompaktes Feld. *Abnahme:* Breite × Höhe stimmt mit `JpegInfo` überein; weißes Testbild liefert Luma ≥ 250, dunkles ≤ 5. *Anforderung:* SV-01.
- [ ] **T2.13** `src/main/kotlin/dev/digiwomb/unboundair/image/PaperDetector.kt` – Papier-Bbox als reine Funktion (Schwelle 60; Zeilen/Spalten mit ≥ 5 % hellen Pixeln; Kanten nach innen, bis ≤ 2 % dunkel, max. 60 px) plus Plausibilitätsprüfung (Fläche ≥ 10 %, Seitenverhältnis ≤ 6,0); `null`, wenn kein Papier. *Abnahme:* `PaperDetectorTest` an handgebauten Luma-Feldern. *Anforderung:* SV-01, SV-02.
- [ ] **T2.14** `src/test/kotlin/dev/digiwomb/unboundair/image/JpegInfoTest.kt` – Maße, Komponenten und iMCU aus der Datei. *Abnahme:* Kuvert 1776×2769, A4 2464×3425, je 3 Komponenten, iMCU 16×8; die synthetischen Bilder liefern ihre tatsächlichen (abweichenden) Werte – belegt, dass gelesen und nicht angenommen wird. *Anforderung:* SV-01, OF-06.
- [ ] **T2.15** `src/test/kotlin/dev/digiwomb/unboundair/image/PaperDetectorTest.kt` – Bbox an Luma-Feldern und echten Bildern. *Abnahme:* Schwarzer Rand → Bbox innen; ganz hell → volles Bild; ganz dunkel → `null`; unplausible Bbox → abgelehnt; echtes A4-Bild → Bbox = volles Bild (Beweis für den bytegleich-Fall); Kuvert → Bbox wie in den Testdefinitionen. *Anforderung:* SV-01, SV-02.

### Testpunkt 2 – `jpegtran` und Zuschnitt

- [ ] **T2.16** `src/main/kotlin/dev/digiwomb/unboundair/image/JpegTran.kt` – führt das externe `jpegtran` aus (`-copy all`, `-crop`, `-grayscale`), prüft den Exit-Code, sammelt stderr, wirft eine eigene Exception mit Meldung. *Abnahme:* `JpegTranTest`. *Anforderung:* SV-01, SV-03.
- [ ] **T2.17** `src/test/kotlin/dev/digiwomb/unboundair/image/JpegTranTest.kt` – Zuschnitt einer vorhandenen Datei; Fehlerfall (keine JPEG-Datei) → Exception, deren Meldung den `jpegtran`-Ausgang nennt. *Abnahme:* Beide Fälle getestet. *Anforderung:* SV-01.
- [ ] **T2.18** `src/main/kotlin/dev/digiwomb/unboundair/processing/ProcessingStep.kt` – Schnittstelle und `PageImage` (Datei + `JpegInfo`); ein Schritt, der nichts ändert, gibt dieselbe `PageImage`-Instanz zurück. *Abnahme:* `PageProcessorTest` hängt den Dummy-Schritt aus SV-07 ein. *Anforderung:* SV-07.
- [ ] **T2.19** `src/main/kotlin/dev/digiwomb/unboundair/processing/PageSettings.kt` – `color-mode` (Default `gray`), `keep-raw` (Default `false`), `minPaperAreaFraction` (0.10), `maxAspectRatio` (6.0); alle Defaults zentral an einer Stelle. *Abnahme:* Die Schritte und Befehle lesen ihre Werte von hier; die Defaults sind dokumentiert. *Anforderung:* SV-02, SV-03, SV-06.
- [ ] **T2.20** `src/main/kotlin/dev/digiwomb/unboundair/processing/CropStep.kt` – Zuschnitt: Bbox + Plausibilität, Ursprung **nach innen** auf die iMCU-Grenze runden, `jpegtran -crop`; deckt der Zuschnitt das ganze Bild ab → Datei unverändert durchreichen; kein Papier oder unplausibel → unbeschnitten übernehmen und Warnung. *Abnahme:* `CropStepTest`. *Anforderung:* SV-01, SV-02.
- [ ] **T2.21** `src/test/kotlin/dev/digiwomb/unboundair/processing/CropStepTest.kt` – die zentralen Abnahmepunkte. *Abnahme:* Kuvert → exakt 1216×2494 px bei +560+56, Luma exakt identisch zum Original-Ausschnitt; A4 → Ausgabe bytegleich zur Eingabe; `dark_page` → unbeschnitten mit Warnung; `a4_bottom_stripe` → Streifen abgeschnitten, oben und seitlich nichts verloren. *Anforderung:* SV-01, SV-02.

### Testpunkt 3 – Graustufen und Schritt-Kette

- [ ] **T2.22** `src/main/kotlin/dev/digiwomb/unboundair/processing/GrayscaleStep.kt` – bei `color-mode=gray`: `jpegtran -grayscale`; bei `color`: unverändert durchreichen. *Abnahme:* `GrayscaleStepTest`. *Anforderung:* SV-03.
- [ ] **T2.23** `src/main/kotlin/dev/digiwomb/unboundair/processing/PageProcessor.kt` – führt eine Liste von Schritten der Reihe nach aus, legt Zwischenergebnisse in einem Arbeitsverzeichnis ab, sammelt Warnungen, liefert ein Ergebnis (Pfad, Maße, Warnungen) und räumt das Arbeitsverzeichnis auf. *Abnahme:* `PageProcessorTest`. *Anforderung:* SV-07.
- [ ] **T2.24** `src/test/kotlin/dev/digiwomb/unboundair/processing/GrayscaleStepTest.kt` – Graustufen. *Abnahme:* `gray`: Ergebnis hat genau eine Komponente, Luma unverändert (Exaktheit laut Testdefinitionen); `color`: drei Komponenten, Datei wird nicht angerührt. *Anforderung:* SV-03.
- [ ] **T2.25** `src/test/kotlin/dev/digiwomb/unboundair/processing/PageProcessorTest.kt` – die Kette. *Abnahme:* Ein Dummy-Schritt lässt sich in die Kette hängen, **ohne einen bestehenden Schritt zu ändern** (SV-07); die Schritte laufen in Reihenfolge; nach dem Lauf sind keine Arbeitsdateien übrig. *Anforderung:* SV-07.

### Testpunkt 4 – Befehle

- [ ] **T2.26** `src/main/kotlin/dev/digiwomb/unboundair/cli/CropCommand.kt` – `crop IN OUT`: führt **nur** den Zuschnitt aus (keine Farbänderung), braucht keinen Scanner. *Abnahme:* `CropCommandTest`. *Anforderung:* BE-03.
- [ ] **T2.27** `src/main/kotlin/dev/digiwomb/unboundair/cli/ScanCommand.kt` – `scan` schreibt die verarbeitete Seite (Zuschnitt + Graustufen laut `color-mode`) unter `--out` bzw. dem Default-Namen; mit `keep-raw` zusätzlich das Roh-JPEG unter demselben Namen mit `_raw` vor der Endung. *Abnahme:* `CommandTest`. *Anforderung:* BE-02, SV-03, SV-06.
- [ ] **T2.28** `src/main/kotlin/dev/digiwomb/unboundair/UnboundAirApplication.kt` – Dispatch um `crop` erweitert; neue Optionen `--color-mode`, `--keep-raw`; zwei Positionsargumente für `crop`; Usage-Text aktualisiert. *Abnahme:* `CommandTest` (Dispatch-Test). *Anforderung:* BE-02, BE-03.
- [ ] **T2.29** `src/test/kotlin/dev/digiwomb/unboundair/cli/CommandTest.kt` – `scan` gegen den Fake-Scanner. *Abnahme:* Der Fake liefert ab jetzt ein echtes JPEG als Payload (das Kuvert-Testbild); ohne Flag liegt nur die verarbeitete Datei vor, mit `--keep-raw` zusätzlich das Roh-JPEG **bytegleich** zum Payload. Der bisherige M1-Test wird entsprechend umgebaut. *Anforderung:* BE-02, SV-06.
- [ ] **T2.30** `src/test/kotlin/dev/digiwomb/unboundair/cli/CropCommandTest.kt` – `crop IN OUT` auf dem Kuvert-Testbild. *Abnahme:* Maße und Datei gleichen dem SV-01-Ergebnis (bytegleich zur Ausgabe des CropStep-Tests); das A4-Bild ergibt bytegleich die Eingabe. *Anforderung:* BE-03.

### Abschluss

- [ ] **T2.31** `docs/plan.md` – Meilenstein 2 abhaken, „Aktuell" fortschreiben. *Abnahme:* Haken gesetzt, nächster Meilenstein benannt; frühestens nach der Abnahme durch den Prüfer. *Anforderung:* Planpflege.
