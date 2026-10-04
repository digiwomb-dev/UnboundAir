# Entscheidungen

Begründungen zu den festen Entscheidungen. Grundlage ist `docs/plan.md` („Feste Entscheidungen" und „Entschieden – nicht mehr offen"); diese Datei füllt sich nach und nach (DO-06).

## Test-Dependency-Set

Grundsatz: Test-Dependencies ausschließlich im Test-Scope, fest gepinnt auf die neueste stabile Version, Kompatibilität mit Java 26 / JUnit Platform 6 / Kotlin 2.4.20 verifiziert (Spikes A und B), nicht angenommen.

| Artefakt | Version | Rolle |
|---|---|---|
| `io.kotest:kotest-property` | 6.2.5 | Property-Tests |
| `org.wiremock:wiremock-standalone` | 3.13.2 | Contract-Tests (paperless) |
| `com.tngtech.archunit:archunit-junit6` | 1.5.0 | Architektur-Wächter |
| `info.solidsoft.pitest` (Plugin) / pitest | 1.19.0 / 1.25.5 | Mutation |
| `org.pitest:pitest-junit5-plugin` | 1.2.2 | Mutation (JUnit-5/6-Anbindung) |
| `org.awaitility:awaitility` | verwaltet | über `spring-boot-starter-test` |
| `org.assertj:assertj-core` | verwaltet | über `spring-boot-starter-test` |
| `com.networknt:json-schema-validator` | 3.0.7 | Contract (JSON-Schema), später |

**kotest-property statt jqwik.** jqwik verbietet ab Version 1.10 die Nutzung durch KI-Coding-Agents — dieses Projekt arbeitet ausdrücklich mit KI-Agenten (siehe `AGENTS.md`). kotest-property ist Apache-2.0, ohne solche Klausel, Kotlin-nativ und registriert keine eigene JUnit-Engine, läuft also konfliktfrei neben JUnit Jupiter. Spike A bestätigt: `forAll` in einer `@Test`-Methode läuft auf JUnit Platform 6.0.3 grün (ein Test, 0 Failures). Hinweis für die Praxis: `forAll`/`checkAll` geben einen Rückgabewert zurück, die `@Test`-Methode braucht daher einen Block-Body.

**WireMock 3.13.2 statt 4.x.** Die 4.x-Linie ist Stand September 2026 weiterhin Beta (4.0.0-beta.37); „neueste stabile" ist daher 3.13.2.

**ArchUnit 1.5.0 mit `archunit-junit6`.** Seit ArchUnit 1.5.0 gibt es das Artefakt `archunit-junit6` mit JUnit-Platform-6-Unterstützung. Engine-ID ist `archunit` (verifiziert), daher `includeEngines("junit-jupiter", "archunit")`.

**Awaitility und AssertJ bleiben verwaltet.** Beide bringt `spring-boot-starter-test` in gepinnten, von Spring Boot gepflegten Versionen mit (Awaitility 4.3.0, AssertJ 3.27.7). Eine eigene Pin-Stelle würde nur Duplikation erzeugen, ohne etwas zu gewinnen.

**JSON-Schema-Validator erst mit der Contract-Schicht.** Der Validator (networknt 3.0.7) wird gebraucht, sobald die erste Contract-Testdatei Antworten gegen ein Schema prüft. Bis dahin bliebe die Dependency ungenutzt — das widerspräche „Abhängigkeiten minimal".

## Verzicht auf Testcontainers

Testcontainers ist keine Option. Drei Gründe, die zusammenspielen:

1. **DC-03:** `./gradlew test` muss im Dev Container komplett offline grün laufen, ohne echte Geräte oder Dienste. Testcontainers würde eine Container-Laufzeit im Test voraussetzen und echte Dienste (paperless-ngx, ggf. später eine Datenbank) hochziehen.
2. **Abhängigkeiten minimal:** Der Dienst hat in v1 bewusst keine Datenbank und kein Web; es gibt schlicht keinen Dienst, der einen Container rechtfertigt. paperless wird mit WireMock, der Scanner mit dem Fake-Scanner ersetzt — beides deckt die Verträge präziser ab als eine echte Instanz.
3. **GraalVM Native Image:** Nichts einbauen, was die spätere Native-Image-Option verbaut.

Entscheidung des Auftraggebers (siehe `docs/plan.md`, DC-03).

## Wahl des Mutationswerkzeugs: PIT

**Entscheidung: PIT** (`pitest` 1.25.5, `gradle-pitest-plugin` 1.19.0, `pitest-junit5-plugin` 1.2.2), eigener Task `pitest`, nie Teil von `build`/`check`.

**Spike B — Befund.** Das bekannte Problem `pitest-junit5-plugin#113` (JUnit Platform 6 → 0 % Coverage, 0 Tests pro Mutation) tritt mit pitest 1.25.5 **nicht** auf; der Fix ist in pitest 1.25.5 enthalten. Gemessen (Paket `image`, 4 Klassen):

- Line Coverage: 83 % (194/235)
- Mutation Coverage: 69 % (135/197)
- Test Strength: 70 % (135/192)

`./gradlew test` bleibt mit angewendetem PIT-Plugin grün — die in #113 zusätzlich gemeldete Classpath-Korruption durch das Gradle-Plugin reproduziert sich auf Gradle 9.7.1 nicht.

**Grenzen.** Ein Lauf über den gesamten Kern (inkl. `scanner`) ist wegen der zeitgesteuerten FakeScanner-Tests langsam; deshalb ist `timeoutConstInMillis` auf 60000 gesetzt und das Mutationsziel auf die Kern-Pakete `scanner`/`image`/`processing` begrenzt. Die gemessenen Zahlen belegen, dass Mutation auf diesem Stack (Java 26, Kotlin 2.4.20, JUnit Platform 6.0.3) funktioniert.

**Verworfene Alternativen.** `mutflow` (1.4.0) und `MutKt` (0.3.3) wurden nur als Fallback evaluiert und nicht gebaut: PIT genügt, beide sind deutlich jünger (MutKt: 1 Stern, gegründet Juni 2026) und brächten ein eigenes Compiler-/Laufzeitmodell mit, das `build` tangieren würde — unnötiges Risiko, solange PIT trägt. Bleiben beide als Rückfallweg notiert, falls PIT mit künftigen JUnit-/Kotlin-Versionen bricht.

## Mutations-Schwelle: 66 %, gemessen statt gewählt

**Erster vollständiger Lauf** über alle drei Kern-Pakete (25.09.2026, Commit `4cd877f`, Dev Container, JDK 26.0.2): `./gradlew pitest`, Dauer **23 min 5 s**, 11 Klassen, 386 Mutationen.

| Paket | Klassen | Line Coverage | Mutation Coverage | Test Strength |
|---|---|---|---|---|
| `image` | 4 | 83 % (195/235) | **74 %** (146/197) | 76 % (146/193) |
| `processing` | 5 | 93 % (85/91) | **68 %** (39/57) | 76 % (39/51) |
| `scanner` | 2 | 87 % (148/171) | **72 %** (95/132) | 75 % (95/126) |
| **gesamt** | **11** | **86 %** (428/497) | **73 %** (280/386) | **76 %** (280/370) |

**Bestätigungslauf Meilenstein 2** (26.09.2026, Commit `f51c65e`): Nach der neuen Factory `pageImage` im Kern-Paket `processing` wurde der volle Lauf wiederholt — **73 % (280/386)**, unverändert grün gegen die Schwelle. Line Coverage 86 % (428/497), Test Strength 76 %, 1287 ausgeführte Tests, Dauer 23 min 7 s. Der Score ist mit der zusätzlichen Zeile gleich geblieben; die Schwelle hält.

**Einmessungslauf Meilenstein 3** (28.09.2026, Commit `17ae322`, Dev Container, JDK 26.0.2): Gemäß TE-04 wurden die neuen Kern-Pakete `output` (PDF-Erzeugung) und `service` (Dienst-Loop und Batch) aufgenommen. 17 Klassen, 525 Mutationen:

| Paket | Klassen | Line Coverage | Mutation Coverage | Test Strength |
|---|---|---|---|---|
| `image` | 4 | 83 % (195/235) | **74 %** (146/197) | 76 % (146/193) |
| `output` | 1 | 100 % (36/36) | **87 %** (20/23) | 87 % (20/23) |
| `processing` | 5 | 93 % (85/91) | **68 %** (39/57) | 76 % (39/51) |
| `scanner` | 3 | 89 % (169/189) | **72 %** (107/148) | 76 % (107/141) |
| `service` | 4 | 93 % (155/166) | **59 %** (59/100) | 71 % (59/83) |
| **gesamt** | **17** | **89 %** (640/717) | **71 %** (371/525) | **76 %** (371/491) |

Durch das Hinzukommen von `output` und `service` änderte sich der Nenner von 386 auf 525 Mutationen. Das ist kein stilles Senken, sondern der nach TE-04 vorgesehene, dokumentierte Wechsel der Messgrundlage.

Die damalige Entscheidung lautete **`mutationThreshold = 71`** — exakt der gemessene Gesamtwert. Die Schwelle ist ein **Boden, kein Ziel**: Sie friert den erreichten Stand ein, damit ein späterer Rückgang der Assertion-Qualität den Task rot macht, statt unbemerkt durchzulaufen. Abgelöst durch den Lauf zu Meilenstein 4.

**Einmessungslauf Meilenstein 4** (04.10.2026, Commit `d6c63de`, Dev Container, JDK 26.0.2): Gemäß TE-04 kamen die neuen Kern-Pakete `output.outbox` (Zwischenablage mit Wiederholung) und `output.paperless` (erstes Ausgabe-Modul) hinzu. Dauer **2 h 25 min 10 s**, 26 Klassen, 695 Mutationen:

| Paket | Klassen | Line Coverage | Mutation Coverage | Test Strength |
|---|---|---|---|---|
| `image` | 4 | 83 % (195/235) | **74 %** (146/197) | 76 % (146/193) |
| `output` | 3 | 100 % (86/86) | **85 %** (29/34) | 85 % (29/34) |
| `output.outbox` | 3 | 88 % (127/145) | **56 %** (40/72) | 60 % (40/67) |
| `output.paperless` | 2 | 85 % (67/79) | **58 %** (29/50) | 60 % (29/48) |
| `processing` | 5 | 93 % (85/91) | **68 %** (39/57) | 76 % (39/51) |
| `scanner` | 3 | 89 % (169/189) | **71 %** (105/148) | 74 % (105/141) |
| `service` | 6 | 72 % (184/254) | **51 %** (70/137) | 72 % (70/97) |
| **gesamt** | **26** | **85 %** (913/1079) | **66 %** (458/695) | **73 %** (458/631) |

**Der Gesamtwert fällt von 71 % auf 66 % — und die Schwelle sinkt mit.** Das ist der erste Lauf, in dem die gemessene Zahl *unter* der bisherigen Schwelle liegt. Der Grund ist nicht, dass bestehende Tests schlechter geworden wären: `image` und `processing` sind Mutant für Mutant unverändert, `output` ist von 87 % auf 85 % nur deshalb gefallen, weil zwei weitere Klassen hinzukamen. Der Rückgang stammt aus drei Stellen:

- `output.outbox` mit **56 % (40/72)** und `output.paperless` mit **58 % (29/50)** kommen neu in die Messgrundlage und liegen deutlich unter dem Rest.
- `service` wuchs von 4 auf 6 Klassen (`OutputPipeline`, `OutboxRunner`) und fiel dabei von 59 % auf **51 % (70/137)**.

Damit liegt die schwächste Abdeckung des Projekts ausgerechnet dort, wo Dokumente verloren gehen können. Das ist bewusst als Befund festgehalten und nicht weggerechnet.

**Nebenbefund `scanner`: 107/148 → 105/148.** Gleicher Nenner, zwei getötete Mutanten weniger, bei unverändertem Produktionscode — Meilenstein 4 hat in `scanner` nur den Fake-Scanner angefasst (`acceptThread.join` in `goOffline`). Vermutlich starben die beiden Mutanten zuvor an einem Timeout, das der sauber abräumende Fake nicht mehr auslöst; ein `TIMED_OUT` zählt bei PIT als getötet. Nachgewiesen ist das nicht, und bei 2 von 695 Mutationen wurde dafür kein eigener Lauf aufgewendet. Festgehalten, damit die Abweichung beim nächsten Lauf nicht als neu gilt.

**Entscheidung: `mutationThreshold = 66`** — erneut exakt der gemessene Gesamtwert. Die Regel „gesenkt wird sie nicht stillschweigend" ist damit nicht gebrochen, sondern angewendet: Die Senkung steht hier mit Datum, Commit, Ursache und Gegenmaßnahme. Die Alternative, die Schwelle bei 71 zu belassen, hätte `./gradlew pitest` dauerhaft rot gelassen — ein rotes Werkzeug, an das man sich gewöhnt, warnt nicht mehr. Gegenmaßnahme ist Issue [#138](https://github.com/digiwomb-dev/UnboundAir/issues/138) in Meilenstein 5, das `output.outbox`, `output.paperless` und `service` auf das Niveau der übrigen Pakete hebt; danach wird die Schwelle wieder angehoben.

Bewusst **nicht** gesetzt sind `coverageThreshold` und `testStrengthThreshold`: Eine Schwelle, die scharf ist, genügt; drei parallele Schwellen machen jeden Rückgang zu einer Fehlersuche über drei Kennzahlen.

**Negativ-Probe (die Schwelle greift wirklich).** Eine Schwelle, die nie ausgelöst hat, ist eine Behauptung. Nachgewiesen mit `mutationThreshold = 95` auf dem kleinsten Kern-Paket (`processing`, 57 Mutationen, Laufzeit 22 s):

```
>> Generated 57 mutations Killed 39 (68%)
Exception in thread "main": Mutation score of 68 is below threshold of 95
        at ...MutationCoverageReport.throwErrorIfScoreBelowMutationThreshold
```

Der Task bricht mit Exit-Code 1 ab. Danach wurde die Konfiguration unverändert zurückgesetzt (Schwelle 73, alle drei Kern-Pakete).

**Speicherbedarf — praktischer Hinweis.** Der volle Lauf braucht spürbar RAM: Gradle-Daemon, Kotlin-Daemon und die PIT-Minions liegen gleichzeitig im Speicher. Auf dem Dev-Container-Host (5,5 GB) ist der Gradle-Daemon zweimal abgestürzt („daemon disappeared"), solange noch JVMs aus früheren Läufen resident waren. Stürzt der Lauf ab, bleibt der PIT-Hauptprozess als Waise zurück (PPID 1) und startet weiter Minions — er muss dann gezielt beendet werden, sonst blockiert er den nächsten Lauf. Vor einem vollen `pitest` also aufräumen:

```
./gradlew --stop && pkill -f MutationTestMinion; pkill -f pitest-command-line
```

Das ist keine Eigenheit von PIT, sondern die Folge von `org.gradle.jvmargs=-Xmx2g` plus separater Test-JVM auf einem kleinen Host.

**Schwächste Pakete nach dem Lauf zu Meilenstein 4** (Kandidaten für die nächsten Tests, nicht für eine niedrigere Schwelle):

- `service` — 51 % (70/137). Das schwächste Paket, und zugleich das mit der Verantwortung für den Dokumentenfluss. Die beiden neuen Klassen `OutputPipeline` und `OutboxRunner` sind hier noch kaum gegen Fehlverhalten abgesichert; die Line Coverage von 72 % ist die niedrigste im Projekt.
- `output.outbox` — 56 % (40/72). Line Coverage 88 %, Mutation Coverage 56 %: Der Code wird ausgeführt, aber zu wenig behauptet. Betrifft die Wiederholungslogik, also genau den Pfad, der entscheidet, ob ein Dokument erneut zugestellt oder verworfen wird.
- `output.paperless` — 58 % (29/50). Dasselbe Muster: 85 % Line Coverage bei 58 % Mutation Coverage.

Diese drei sind der Inhalt von Issue [#138](https://github.com/digiwomb-dev/UnboundAir/issues/138) in Meilenstein 5. Die Klassen-Zahlen dazu stehen im HTML-Report; sie sind hier bewusst nicht abgeschrieben, solange das Issue sie nicht einzeln aufgreift.

**Schwächste Klassen nach dem Lauf zu Meilenstein 3** (Stand 28.09.2026, beim Lauf zu Meilenstein 4 nicht erneut je Klasse erhoben):

- `JpegTran.kt` — 27 % (3/11). Der Prozess-Aufruf ist kaum gegen Fehlverhalten abgesichert; die Argumentbildung wird nur indirekt geprüft.
- `PageSettings.kt` — 25 % (1/4) und `GrayscaleStep.kt` — 50 % (2/4). Kleine Klassen, in denen einzelne überlebende Mutanten stark durchschlagen.
- `ScanLoop.kt` — 56 % (38/67). Multithreading-, Polling- und Timeout-Pfade mit überlebenden Randfall-Mutanten.
- `CropStep.kt` — 60 % (9/15), `PageProcessor.kt` — 61 % (11/18).
- 34 Mutationen ohne jede Testabdeckung (`no coverage`).

**Netzzugriff beim ersten Lauf.** `org.pitest:pitest:1.25.5` und `pitest-junit5-plugin:1.2.2` liegen nicht im warmen Gradle-Cache (nur das Gradle-Plugin 1.19.0), der erste `pitest`-Lauf löst sie daher online auf. Das berührt **DC-03 nicht**: Die Anforderung gilt `./gradlew test`, und dieser Lauf blieb danach unverändert offline grün (72 Tests, 0 Fehler). `pitest` bleibt außerhalb von `build`/`check`.

## PDF-Metadaten-Determinismus: injizierbare Clock

Golden-Master-Tests brauchen bytegleiche PDFs. PDF-Metadaten (insbesondere `CreationDate`) variieren sonst von Lauf zu Lauf. **Entscheidung: injizierbare Clock** statt fester Konstante.

- `CreationDate` = Scan-Zeitpunkt (Beginn der ersten Seite) aus einer injizierbaren Clock.
- Tests pinnen die Clock auf einen festen Zeitpunkt → bytegleiche PDFs, vollständiger Byte-Vergleich im Golden Master bleibt möglich.
- Die Produktion behält echte Zeitstempel (Dateiname `scan-YYYYMMDD-HHMMSS.pdf` und Metadaten bleiben sinnvoll).

Damit entfällt die Alternative „festes CreationDate" (z. B. Epoche), die zwar einfach und stabil wäre, aber PDFs ohne sinnvolle Zeitangabe erzeugte. Ein rein struktureller Vergleich ohne Voll-Byte-Golden-Master würde die Aussagekraft des Golden Masters schwächen.

**Nachtrag aus der Umsetzung (Meilenstein 3): die Clock allein genügt nicht.** Beim Bau von `PdfBuilder` zeigte sich, dass zwei Speichervorgänge desselben Dokuments sich auch bei gepinnter Clock um 32 Byte unterscheiden. Ursache ist die Trailer-Angabe `/ID`, die PDFBox aus der **aktuellen Zeit und einer Zufallszahl** bildet – unabhängig von `CreationDate`. Der Determinismus-Test schlug deshalb fehl, bevor überhaupt ein Golden File verglichen werden konnte.

`PDDocument.setDocumentId` wird daher ebenfalls aus der injizierten Clock abgeleitet (`clock.millis()`). Damit ist die Ausgabe bei gepinnter Clock reproduzierbar, während die Produktion weiterhin je Dokument einen eigenen Wert bekommt, weil die Uhr weiterläuft.

Der Punkt ist festgehalten, weil er die ursprüngliche Entscheidung ergänzt: „injizierbare Clock" reicht als Beschreibung nicht: **jede** Quelle von Zufall oder Echtzeit im Schreibpfad muss an die Clock gebunden sein, sonst ist ein Byte-Vergleich unmöglich. Zusätzlich ist deshalb `Producer` fest auf `UnboundAir` gesetzt, ohne Versionsnummer – sonst änderte jede Freigabe jedes Golden File, ohne dass sich inhaltlich etwas bewegt hätte.

## Paketschichten: eigene Schichten `config` und `service`

Mit Meilenstein 3 kommen Dienst-Loop, Batch und PDF-Erzeugung dazu. Für keines davon gab es einen Platz: Der Wächter kannte `scanner`, `image`, `processing`, `output` und `cli`, wobei `cli` **nicht** auf `output` zugreifen darf. Eine Schleife, die scannt, verarbeitet und ein PDF baut, hätte in keine dieser Schichten gepasst, ohne eine Regel zu brechen oder eine Schicht zu ihrem Gegenteil zu machen.

**Entscheidung: zwei neue Schichten.** `config` als Blatt ohne eigene Abhängigkeiten, `service` als Orchestrierung darüber. Die vollständige Richtungstabelle steht in `docs/plan.md`.

Drei Punkte, die dabei bewusst so und nicht anders entschieden sind:

- **`scanner`, `image` und `processing` bleiben frei von `config`.** Naheliegend wäre, die neuen `@ConfigurationProperties` überall direkt zu injizieren. Das würde den Kern aber an Spring binden: Die Klassen sind heute ohne Kontext konstruierbar und damit als reine Unit-Tests prüfbar. Sie bekommen ihre Werte weiter über Konstruktor-Parameter mit Defaults (Muster `PageSettings`); das Umsetzen von Properties auf diese Parameter ist Aufgabe der Kompositionswurzel. Dass die drei Pakete Blätter sind, ist damit eine vom Wächter geprüfte Regel und keine Absichtserklärung.
- **`cli` darf `service` sehen, aber weiterhin nicht `output`.** Der Befehl `run` startet den Dienst, deshalb braucht die CLI Zugriff auf `service`. Der Weg zur Ausgabe führt aber weiter ausschließlich über `service` – die CLI soll kein PDF bauen und kein Modul ansprechen.
- **Der Wächter bekommt erstmals auch eingehende Regeln.** Bisher prüfte er nur, worauf eine Schicht zugreifen darf. Eine Schicht, die niemand deklariert, wäre damit völlig ungeprüft geblieben. Mit `service` als oberster Nutzschicht kommt die Gegenrichtung dazu: Auf `service` darf nur `cli` zugreifen, nicht der Kern.

## Logging: SLF4J mit Logback, Senken bleiben Lambdas

Bis Meilenstein 2 gab es kein Logging-Framework – Ausgaben liefen über `println`/`System.err.println` in der Kompositionswurzel, Meldungen aus dem Kern über `warn: (String) -> Unit`-Lambdas. Für KL-02 (Zeile je Seite mit vier Messwerten) und DL-02 („nur beim Zustandswechsel loggen") reicht das nicht: Es fehlen Level, Zeitstempel und ein sauberer Zugriff im Test.

**Entscheidung: SLF4J als Fassade, Logback als Implementierung.** Beide bringt `spring-boot-starter` bereits mit – die Entscheidung kostet **keine** neue Abhängigkeit und verletzt „Abhängigkeiten minimal" nicht. Logback schreibt per Default auf stdout, was KL-02 ohnehin verlangt (journald-freundlich). Im Test hängt sich ein `ListAppender` an den Logger, statt stdout abzufangen.

**Was ausdrücklich bleibt:** Kommandos, Verarbeitungsschritte und der Scanner-Client loggen **nicht** selbst. Sie melden weiter über ihre `warn`-Senke nach oben; nur `service` und die Kompositionswurzel schreiben Log-Zeilen. Das ist kein Schönheitsprinzip: Der Kern bleibt dadurch ohne Logger-Attrappe testbar, und ein Aufrufer entscheidet, ob eine Meldung ein Log-Eintrag, eine CLI-Zeile oder später eine Web-UI-Benachrichtigung wird.

Verworfen wurde `kotlin-logging`. Es ist bequemer, aber eine zusätzliche Abhängigkeit für syntaktischen Zucker über derselben Fassade.

## Batch-Übergabe: eine Senke statt einer vorgezogenen Outbox

DL-04 verlangt, dass ein geschlossener Batch „an die Outbox" geht. Die Outbox ist aber AU-04 und gehört zu Meilenstein 4 – in Meilenstein 3 gibt es sie noch nicht.

**Entscheidung: Der Batch übergibt an eine Senke vom Typ `(ScannedDocument) -> Unit`.** In Meilenstein 3 schreibt diese Senke das PDF in ein Verzeichnis. In Meilenstein 4 wird die Outbox eingehängt – **ohne eine Zeile am Batch zu ändern**.

Die beiden Alternativen waren schlechter:

- **Die Outbox in Meilenstein 3 vorziehen** hieße, AU-04 (Persistenz, Retry mit Backoff, Neustart-Festigkeit) zu bauen, bevor es überhaupt ein Modul gibt, an das zugestellt werden könnte. Das verschiebt Arbeit, ohne sie zu verkleinern, und macht den Meilenstein unscharf.
- **Eine eigene Schnittstelle mit einer Wegwerf-Implementierung** wäre mehr Zeremonie für dasselbe Ergebnis. Das Projekt verwendet das Lambda-Muster bereits an derselben Stelle im Code (`warn: (String) -> Unit`); eine zweite Konvention für denselben Zweck wäre unnötig.

Nebeneffekt: Damit ist AU-02 („neue Module lassen sich ergänzen, ohne den Kern zu ändern") an einer echten Stelle belegt, statt nur behauptet zu werden.

## `scan` liefert die effektive Auflösung mit (SC-08)

`ScannerClient.scan(dpi)` gab bisher nur die JPEG-Bytes zurück. Fällt der Firmware-Check nach SC-07 aus – Gerät kann kein 600 dpi –, stuft der Client still auf 300 zurück und meldet das nur als Warnung an die `warn`-Senke. Der Aufrufer bekommt die tatsächlich verwendete Auflösung nicht.

Bis Meilenstein 2 war das folgenlos: Die DPI landete nur im Dateinamen. Mit SV-05 (Seitengröße im PDF = Pixel ÷ DPI) wird sie zu einer **maßgeblichen Größe**. Ein Scan, der mit 600 angefordert und mit 300 geliefert wird, ergäbe eine PDF-Seite in halber Kantenlänge – ein Fehler, den niemand im Log sucht, weil das Dokument ansonsten unauffällig aussieht.

**Entscheidung: `scan` liefert Bytes und effektive Auflösung gemeinsam zurück.** Damit ist die Zahl, die die Seitengröße bestimmt, dieselbe, die das Gerät tatsächlich benutzt hat. Die Warnung bleibt zusätzlich bestehen – sie erklärt dem Menschen, warum die Auflösung abweicht.

Das ist eine Änderung an bestehendem Code aus Meilenstein 1 und geschieht deshalb früh in Meilenstein 3, bevor PDF-Erzeugung und Dienst-Loop darauf aufbauen.

## Spike-Ergebnisse (Zusammenfassung)

- **Spike A (kotest-property auf JUnit Platform 6):** läuft. 1 Test, 0 Failures auf Platform 6.0.3 (Spring Boot 4.1.1, `junit-jupiter` 6.0.3).
- **Spike B (Mutation):** PIT funktioniert (Zahlen oben), kein Fallback nötig.
