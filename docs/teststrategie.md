# Teststrategie

Wie `UnboundAir` getestet wird: die Testschichten, die Werkzeuge je Schicht und die Gründe dafür. Die Strategie ist die fachliche Grundlage für alle Test-Issues; die einzelnen Entscheidungen dahinter stehen mit Begründung in `docs/entscheidungen.md`, die fest gepinnten Versionen in `docs/plan.md`.

## Grundsätze

- **Offline (DC-03):** `./gradlew test` läuft im Dev Container komplett ohne Zugriff auf echte Geräte oder fremde Dienste. Der Scanner wird durch einen Fake-Scanner (TCP-Server im Test) ersetzt, paperless-ngx durch einen Mock. Eine Netzwerkverbindung braucht nur, wer zum ersten Mal baut (Gradle-Wrapper, Abhängigkeiten) — danach läuft der Testlauf mit warmem Cache offline.
- **Keine Testcontainers.** Test-Dependencies gibt es ausschließlich im Test-Scope; der Runtime-Classpath bleibt unverändert.
- **Versionen fest gepinnt** (Grundsatz „neueste stabile", siehe `docs/plan.md`).
- **Standard-Assertions: AssertJ.** Testnamen in Backticks und mit der umgesetzten Anforderungs-ID (z. B. `SC-01 …`).
- **Eine Datei pro Aufgabe** (zweistufiges GitHub-Tracking, siehe unten).

## Die Testschichten

Die Labels im GitHub-Tracking sind deckungsgleich mit den Schichten: `unit`, `property`, `slice`, `integration`, `contract`, `e2e`, `golden-master`, `mutation`.

**Kein Container-Check als Schicht.** Die CT-01-Abnahme (`.github/workflows/image.yml` auf `ubuntu-24.04-arm`: Image bauen, darin `jpegtran -version`, `jbig2 -V` und `status` gegen den standalone gestarteten Fake-Scanner prüfen) ist ein zusätzliches Tor **außerhalb** von `./gradlew test`, keine neunte Schicht. Sie schwächt DC-03 nicht: `./gradlew test` läuft weiterhin vollständig offline gegen Fake-Scanner und Mock, ohne echtes Gerät und ohne fremden Dienst.

### 1. Unit (`unit`)

Reine JVM-Tests für einzelne Einheiten ohne Spring-Kontext, ohne Netz und ohne externes Programm. Assertions mit **AssertJ**.

- **Warum:** schnell, deterministisch, die breite Basis. Der Kern (Scanner-Client, Verarbeitung, Batch, Ausgabe) besteht aus kleinen, testbaren Einheiten.
- **Abgrenzung:** alles, was einen Spring-Kontext, einen TCP-Socket oder `jpegtran` braucht, gehört in eine tiefere Schicht.

### 2. Property (`property`)

Eigenschaftsbasierte Tests mit **kotest-property** (`forAll`/`checkAll`), aufgerufen aus gewöhnlichen JUnit-Jupiter-`@Test`-Methoden. kotest-property registriert keine eigene Engine, deshalb läuft es neben den übrigen Tests auf derselben JUnit Platform.

- **Warum:** deckt Randfälle ab, die handgeschriebene Beispiele übersehen — wichtig für den Auto-Zuschnitt (SV-01/SV-02), wo beliebige Bildgeometrien eintreffen.
- **Achtung:** `forAll`/`checkAll` geben einen Rückgabewert zurück; die `@Test`-Methode muss deshalb einen Block-Body haben (kein `= runBlocking { … }`-Ausdruckskörper), sonst verweigert JUnit die Ausführung.
- **Warum nicht jqwik:** jqwik führt ab 1.10 eine Nutzungsbeschränkung ein und schreibt absichtlich Fremdtext in die Standardausgabe. Begründung in `docs/entscheidungen.md`.

### 3. Slice (`slice`)

Gezielte Spring-Boot-Slices statt voller Kontext. Konkret:

- **`@JsonTest`** für JSON-Serialisierung/Deserialisierung (z. B. `metadata.json` der Outbox, AU-04).
- **Config-Binding:** Tests für `@ConfigurationProperties` unter `unboundair.*` (KL-01) — jede Einstellung mit Default, Umgebungsvariable überschreibt.

  **Fallstrick, in Meilenstein 3 gefunden:** Spring wendet seine „relaxed binding"-Regel (`UNBOUNDAIR_POLLINTERVAL` → `unboundair.poll-interval`) nur auf eine `SystemEnvironmentPropertySource` an, **deren Name `systemEnvironment` ist oder auf `-systemEnvironment` endet**. Eine Quelle der richtigen Klasse unter einem anderen Namen wird stillschweigend ignoriert, und `withPropertyValues` legt ohnehin eine gewöhnliche Map-Quelle an. Ein Test, der das nicht beachtet, ist grün und beweist das Gegenteil der Anforderung.
- **Context-Smoke:** ein einziger Test, der den ApplicationContext hochzieht und bestätigt, dass die Konfiguration auflösbar ist (fängt Verdrahtungsfehler, ohne echtes Verhalten zu prüfen).
- **`MockRestServiceServer`** für den paperless-Client gegen einen gemockten `RestClient` (AU-05) — ohne HTTP-Server, wenn nur die Request-Seite zählt.

- **Warum:** prüft die Spring-Verdrahtung billig und gezielt, wo ein voller Kontext unnötig teuer wäre.
- **Abgrenzung:** `@WebMvcTest` und `@DataJpaTest` entfallen (siehe „Was fehlt und warum").

### 4. Integration (`integration`)

Zusammenwirken mehrerer echter Bausteine, aber ohne echte Geräte/Dienste:

- **FakeScanner-TCP** (TE-01): der Scanner-Client spricht über einen echten TCP-Socket mit dem im Test laufenden Fake-Scanner (Füllbytes, geteilte `jpegsize`-Antwort, `devbusy`, Offline, `battlow`). Seit Meilenstein 3 zusätzlich skriptfähig: Blattfach für mehrere Seiten, Statusfolgen und ein **umschaltbares Offline auf demselben Port**, ohne das sich ein Dienst-Loop nicht prüfen ließe. Dieselbe Klasse läuft seit Meilenstein 5 zusätzlich **standalone** (`FakeScannerMain`, Gradle-Task `fakeScanner`) für den Container-Check — bewusst keine zweite Fake-Implementierung: Zwei Fakes driften auseinander und entwerten die Contract-Tests stillschweigend.
- **`jpegtran`**: Zuschnitt/Graustufen laufen über das echte externe Programm (SV-01, SV-03) — dieselbe Systemabhängigkeit wie im Laufzeit-Image.
- **`jbig2`**: 1-bit-Schwarz-Weiß-Encodierung (SV-08) läuft über das echte externe Programm — dieselbe Systemabhängigkeit wie im Laufzeit-Image.
- **Awaitility + injizierbare Clock**: asynchrone Abläufe (Dienst-Loop DL-01 bis DL-07, Outbox-Retry AU-04) werden mit Awaitility synchronisiert, Zeit mit einer injizierbaren Clock gesteuert statt `Thread.sleep`.
- **Aufgezeichneter Sleeper statt echtem Warten:** Der Dienst-Loop bekommt seine Wartefunktion als `(Duration) -> Unit` injiziert. Die Tests reichen eine Funktion herein, die die angeforderten Abstände nur **aufschreibt**. Damit wird aus einer Taktregel eine gewöhnliche Zusicherung; echtes Warten auf verkürzte Intervalle wäre ein Wettlauf, kein Test.
- **Log-Zusicherungen über einen `ListAppender`** statt über abgefangenes stdout: Die Einträge kommen als Objekte, lassen sich zählen und nach Level filtern, und ein geändertes Log-Muster macht die Tests nicht rot.

**Ein Integrationstest liegt im Paket der obersten beteiligten Schicht.** Der Wächter prüft Testklassen wie jede andere Klasse, und die Schichtenregel gilt für sie genauso: Ein Test in `output.outbox`, der `service.OutboxRunner` antreibt, verletzt sie – 36-mal, beim Outbox-Retry-Test aus Meilenstein 4 gemessen. Die Lösung ist, den Test zu verschieben, nicht die Regel aufzuweichen. Anders als beim Slice-Test, der Spring hochfahren *muss*, um das ausgelieferte Objekt zu prüfen, hat ein schichtübergreifender Test einen offensichtlichen anderen Weg: das richtige Paket. Darum liegen alle Integrationstests des Dienst-Loops in `service`.

**Zwei Erfahrungen aus Meilenstein 3, die für jeden weiteren nebenläufigen Test gelten:**

1. **Awaitility-Grenzen großzügig wählen.** Eine Statusabfrage kostet nach SC-02 rund 0,7 s an vorgeschriebenen Pausen – unabhängig vom eingestellten Intervall. Sechs Abfragen brauchen also über vier Sekunden, bevor die übrige Testsuite um dieselbe Maschine konkurriert. Eine 10-Sekunden-Grenze war allein grün und im vollen `build` rot. Die Grenze wird nie ausgeschöpft, wenn alles funktioniert; eine großzügige kostet nichts, eine knappe erkauft sporadische Fehlschläge.
2. **Auf den Zustand warten, nicht auf eine Anzahl Durchläufe.** Der Loop dreht viele Runden, während der Fake-Scanner seinen Port neu bindet. Ein Test, der „noch vier Abfragen" abwartet, ist fertig, bevor die Zustandsänderung überhaupt eingetreten ist.

**Langlebige Kommandos testen (`run`, DL-07):** Anders als alles davor hält `run` zwei Threads und endet erst auf ein Signal. Seine Tests starten es, beobachten es und halten es wieder an — dafür gelten drei Regeln:

1. **Alles Warten mit Awaitility, niemals `Thread.sleep`.**
2. **Die Zeit kommt aus einer injizierbaren Clock.**
3. **Der Test weist nach, dass die Threads wirklich enden.** Ein Test, der einen Thread laufen lässt, macht einen *späteren, unbeteiligten* Test flaky — den am schwersten aufzuspürenden Fehler in diesem Projekt.

**Den Shutdown-Pfad testen, nicht das Signal:** Geprüft wird der Körper des Shutdown-Hooks — derselbe `stop()`-Pfad, den das Signal auslösen würde —, aufgerufen aus dem Test-Thread. Die Signal-Registrierung (`Runtime.addShutdownHook`) selbst bleibt bewusst ungeprüft: Die JVM zu töten hieße, den Gradle-Worker zu töten, und der Build wäre rot statt grün. Das ist die allgemeine Regel für jeden Shutdown-Test, kein Einzelfall.

- **Warum:** die riskantesten Stellen des Dienstes sind die Protokoll-/Zeit- und Prozessgrenzen; genau die werden hier mit den echten Mechanismen (Socket, externes Programm) geprüft.
- **Prozess-Wrapper hart testen — Lehre aus `JpegTran`:** Prozessaufrufe lassen sich leicht unter-assertieren, und das rächt sich messbar: `JpegTran` ist mit 27 % Mutation Coverage die schwächste Klasse des Projekts. `Jbig2Enc` ist bewusst härter geprüft (Integrationstest über beide Fehlermodi, den Anzahl-Wächter und die Größenrelation — Issue #159). Der nächste Prozess-Wrapper startet von dieser Lehre, statt sie neu zu entdecken.

### 5. Contract (`contract`)

Schnittstellenverträge nach außen:

- **WireMock** als paperless-ngx-Ersatz (AU-05): Pfad `/api/documents/post_document/`, Multipart-Feld `document`, Header `Authorization: Token …`, optionale Tag-Felder, Dateiname `scan-YYYYMMDD-HHMMSS.pdf`, Task-UUID aus der Antwort.
- **JSON-Schema**: Antworten werden gegen ein Schema validiert (Werkzeug `com.networknt:json-schema-validator`, wird mit der ersten Contract-Testdatei fest gepinnt).
- **FakeScanner-Transkript** (SC-01): ein aufgezeichnetes Sitzungs-Transkript (Befehle/Antworten) dient als Golden-Source, gegen die der Client bytegenau geprüft wird.

- **Warum:** wir sind Consumer der paperless-API; der Vertrag sichert, dass das Modul auch bei API-Änderungen auf unserer Seite sofort bricht — ohne echtes paperless.
- **Abgrenzung:** Spring Cloud Contract entfällt (wir sind reiner Consumer, siehe unten).

### 6. E2E offline (`e2e`)

Der ganze Dienst offline: `run` gegen Fake-Scanner **und** WireMock-paperless. Drei Seiten → ein dreiseitiges PDF, an das Modul übergeben und hochgeladen; Scanner offline schließt den Batch; Outbox-Retry nach Neustart. Kein echtes Gerät, kein echtes paperless. Seit Meilenstein 5 startet der E2E-Test dabei den ausgelieferten `run`-Einstiegspunkt selbst (Adresse und paperless-URL allein über Spring-Properties, kein `--host`-Flag) — was der Anwender startet, startet der Test.

- **Warum:** bestätigt, dass die Bausteine im Zusammenspiel das Ergebnis aus `docs/plan.md` („Ergebnis", Punkt 4) liefern.

### 7. Golden Master (`golden-master`)

Byte-genaue Referenzartefakte unter `golden/` mit einem **sha256-Manifest**. Ergebnisdateien (zugeschnittene JPEGs, PDFs) werden gegen den gespeicherten Stand verglichen.

- **Warum:** schützt vor stillen Regressionen, wo „ungefähr richtig" nicht reicht (verlustfreier Zuschnitt, JPEG-Einbettung roh als `/DCTDecode`).
- **Producer/Verifier-Trennung:** Das PDF wird von **OpenPDF** geschrieben und von **PDFBox** zurückgelesen — und dass das zwei verschiedene Bibliotheken sind, ist der Punkt. Ein Golden-Master-Test, dessen Leser sein eigener Schreiber ist, bestätigt nur, dass der Schreiber mit sich selbst konsistent ist; das wollte niemand wissen. Die unabhängige Gegenprüfung mit einer zweiten Bibliothek ist das schärfste Beispiel für unabhängige Verifikation in diesem Projekt.
- **PDF-Determinismus:** PDF-Metadaten (CreationDate) stammen aus der **injizierbaren Clock** (Scan-Zeitpunkt = Beginn der ersten Seite). Tests pinnen die Clock → bytegleiche PDFs; die Produktion behält echte Zeitstempel. Begründung in `docs/entscheidungen.md`. **Die Clock allein genügt nicht:** OpenPDFs Trailer-Schreiber übernimmt die Angabe `/ID` aus dem `/FileID`-Eintrag des Info-Wörterbuchs, wenn vorhanden, und erzeugt sonst 16 Zufallsbytes (Spike #141); `PdfBuilder` pinnt `/ID` über ebendiese Nahtstelle, dazu `/CreationDate`/`/ModDate` aus der injizierten Clock und einen festen `/Producer`. Die Lehre verallgemeinert: Eine Engine findet einen Weg, die aktuelle Zeit in ein PDF zu schreiben — herauszufinden, wo, gehört zur Arbeit.
- **„Nicht neu komprimiert" muss man byteweise prüfen.** Ein Test, der nur die JPEG-Marker `SOI`/`EOI` kontrolliert, sieht gut aus und beweist nichts: Diese Marker überstehen eine Neukodierung unverändert. In Meilenstein 3 blieb genau so ein Test grün, während jedes Pixel durch einen zweiten verlustbehafteten Durchgang gelaufen war. Verglichen wird deshalb der **rohe, noch komprimierte Datenstrom** (`COSStream.createRawInputStream`) gegen die Eingabedatei — `toByteArray()` und `createInputStream()` dekodieren und taugen dafür nicht.
- **In Meilenstein 4 bewusst nicht eingesetzt.** Die Ausgabe-Module erzeugen kein neues bytegenaues Artefakt: Das PDF ist bereits durch `PdfGoldenTest` festgenagelt, und die Outbox kopiert es unverändert. Bliebe `metadata.json` — dafür ist der `@JsonTest`-Rundlauf (Schicht Slice) die bessere Prüfung, weil er den Verlust einzelner Felder benennt, während eine Golden-Datei bei jedem neuen Feld rot wird, ohne dass etwas kaputt ist. Eine Schicht wegzulassen, ohne den Grund aufzuschreiben, ist dasselbe wie sie zu vergessen.
- **Neue Golden-Dateien reagieren auf drei gepinnte Versionen.** Die bisherigen Golden-Artefakte hingen an einer (`jpegtran`); die aus diesem Meilenstein hängen an **drei** — `jpegtran`, `jbig2` und OpenPDF. Wird nach einem Container-Neubau eines davon rot, ist die Version der erste Verdächtige — das zu wissen erspart eine Stunde Jagd auf einen Logikfehler, der nicht da ist.

### 8. Mutation (`mutation`)

**PIT** (pitest 1.25.5) über den eigenen Gradle-Task `pitest`, Ziel sind die Kern-Pakete (`scanner`, `image`, `processing`, seit Meilenstein 3 zusätzlich `output` und `service`, seit Meilenstein 4 auch `output.outbox` und `output.paperless`). Der Task ist **nie Teil von `build`/`check`** und läuft nur auf ausdrücklichen Aufruf. Wächst das Ziel, ändert sich der Nenner: Die Schwelle ist dann neu einzumessen und mit Zahlen zu dokumentieren (TE-04) — das ist ein belegter Wechsel der Messgrundlage, kein stilles Senken.

- **Warum:** Mutation deckt Lücken in der Assertion-Qualität auf, die Coverage allein nicht zeigt.
- **Grenzen (gemessen, Spike B):** PIT funktioniert auf JUnit Platform 6 (das bekannte Problem 0 %-Coverage ist mit pitest 1.25.5 behoben). Die zeitgesteuerten Scanner-Tests machen Läufe über den ganzen Kern langsam; deshalb `timeoutConstInMillis` erhöht. Zahlen und Entscheidung in `docs/entscheidungen.md`.
- **Stand (Einmessung Meilenstein 4, 04.10.2026, Commit `d6c63de`):** gesamt **66 %** Mutation Coverage (458/695), Test Strength 73 %, Dauer 2 h 25 min. Mit der Aufnahme von `output.outbox` und `output.paperless` wuchs der Nenner von 525 auf 695 Mutationen — ein belegter Wechsel der Messgrundlage. Alle Zahlen je Paket stehen in `docs/entscheidungen.md`.
- **Schwelle:** `mutationThreshold = 66` in `build.gradle.kts` — der gemessene Wert als **Boden**, damit ein Rückgang den Task rot macht. Anheben, wenn der Score steigt; **nie stillschweigend senken**. Der Lauf zu Meilenstein 4 hat den Boden erstmals gesenkt, von 71 % — nicht stillschweigend, sondern mit Ursache und Gegenmaßnahme in `docs/entscheidungen.md`. Schwächste Pakete sind dort ebenfalls benannt.
- **Neueinmessung zu Meilenstein 5:** Issue #138 schärft die Tests der schwächsten Pakete (`service`, `output.outbox`, `output.paperless`) und misst danach neu; die Schwellenanhebung läuft dort. Zahlen stehen danach — wie bisher — nur in `docs/entscheidungen.md` mit Datum und Commit, nicht hier.
- **Die Schicht `e2e` ist aus den `targetTests` ausgenommen** (gemessen, seit Meilenstein 4). Ihre Tests warten mit Awaitility und einer Obergrenze von 60 Sekunden — richtig für sie, falsch als Mutationsbasis: Eine Mutation, die die Zustellung kaputtmacht, lässt jeden solchen Test seine volle Wartezeit verbrennen, statt schnell rot zu werden. **Gemessen** an denselben 8 Mutationen von `OutputModules`: gegen `e2e` als `targetTests` dauert die Mutationsanalyse **4 min 53 s**, gegen den Unit-Test `OutputModulesTest` **1 s** — rund 37 Sekunden je Mutation gegenüber 0,13, also Faktor ~290. Auf die 695 Mutationen der vollen Messgrundlage hochgerechnet wären das etwa 7 Stunden allein für diesen Anteil. Dabei tötet `e2e` sogar **weniger**: 6 von 8 gegenüber 7 von 8. Die Mutationen in `output` und `service` werden von den Unit-, Slice- und Integrationstests ohnehin erreicht; `e2e` bringt Laufzeit, aber keine zusätzliche Reichweite. Das ist eine Einschränkung der Messgrundlage und steht deshalb hier, nicht nur als Kommentar in `build.gradle.kts`.
- **Netz:** Die `org.pitest`-Artefakte sind nicht im warmen Cache; der erste `pitest`-Lauf löst sie online auf. DC-03 bleibt unberührt, weil es `./gradlew test` betrifft — der läuft weiterhin offline.
- **Ausführungsort: lokal oder auf Abruf in CI** (seit 05.10.2026). Der volle Lauf geht auch über `.github/workflows/pitest.yml` — `workflow_dispatch` auf `dev`, an keinem `push`, auf `ubuntu-24.04-arm`, mit dem Bericht als Artefakt. Entscheidend für diese Schicht: Der Workflow führt `./gradlew pitest` **im Dev-Container-Image** aus (`.devcontainer/Dockerfile`), nicht in einer runner-seitigen Nachbildung. Damit stammen CI-Zahlen und lokale Zahlen aus derselben Umgebung und sind miteinander vergleichbar — eine Messgrundlage, die je nach Ausführungsort andere `jpegtran`-, `jbig2`- oder JDK-Versionen sähe, wäre keine. Der Ablauf steht in `docs/entwicklung.md`, die Begründung in `docs/entscheidungen.md`.

### Wächter (Guard)

Ergänzend zu den Schichten, als eigene Datei je Konzern:

- **ArchUnit** (`archunit-junit6`): Architekturregeln — die Paketschichten `config`, `scanner`, `image`, `processing`, `output`, `service`, `cli` mit ihren Richtungen (Tabelle in `docs/plan.md`); **kein `@ConditionalOnProperty`** im Code (AU-03); **der Kern bleibt frei von Spring**.

  Zwei Regeln sind bewusst anders gebaut als die übrigen: Auf `service` darf **nur `cli`** zugreifen (eine eingehende Regel — ohne sie wäre die oberste Schicht in der Richtung ungeprüft, die am meisten zählt), und `scanner`/`image`/`processing`/`output` dürfen nichts aus `org.springframework` importieren. Letzteres kann die Schichtenregel nicht leisten, weil `org.springframework` zu keiner Schicht gehört; sie greift genau dort, wo die Abkürzung verlockend ist — beim Hineininjizieren von `UnboundAirProperties` in einen Verarbeitungsschritt.
- **Konventionstests:** alle Defaults zentral und in der Doku (KL-01, Referenz in `docs/konfiguration.md`), Exception-Hierarchie (SC-05), Test-Benennung.

## Was fehlt und warum

- **`@WebMvcTest` / `@DataJpaTest`:** Es gibt keine Web-Oberfläche (v1) und keine Datenbank. Beide Slices hätten nichts zu testen.
- **Spring Cloud Contract:** Wir sind Consumer der paperless-API, kein Producer, der Verträge veröffentlicht. Der Vertrag wird deshalb mit WireMock + JSON-Schema auf Consumer-Seite gesichert.
- **Testcontainers:** DC-03 verlangt offline grüne Tests; Testcontainers würde eine Container-Laufzeit im Test und echte Dienste (paperless, später ggf. DB) voraussetzen. Dazu kommt die GraalVM-Native-Image-Option.
- **jqwik:** Anti-AI-Klausel (siehe Property-Schicht und `docs/entscheidungen.md`).

## GitHub-Tracking-Modell

Testarbeit lebt in GitHub, zweistufig: Ein Impl+Test-Paar ist ein Eltern-Issue (Typ `Task`, Label `kind/parent`) mit zwei Sub-Issues – der Umsetzung (Typ `Feature`, `kind/feat`) und dem Test (Typ `Test`, `kind/test`). Eine Aufgabe umfasst genau eine Datei; eine Dateiliste nur, wenn ein Anliegen tatsächlich über mehrere reicht. Der Grund ist die Prüfbarkeit: Eine Aufgabe über eine Datei lässt sich für sich lesen, prüfen und abnehmen. Das vollständige Titel- und Label-Schema steht in `CONTRIBUTING.md` unter „Issues".

Ein Test-Issue entsteht aus `.github/ISSUE_TEMPLATE/test.yml` und trägt damit automatisch Typ, Rolle-Label und die feste Checkliste. Zwei Dinge sind dabei wichtig:

- Im Titel steht **die Schicht**, nicht das Paket: `test(unit): DL-04 …`, nicht `test(service): …`. Die Schicht ist die fachliche Einordnung aus diesem Dokument und sagt, wie geprüft wird – das Paket steht ohnehin im Dateipfad.
- Dieselbe Schicht wird zusätzlich als Label gesetzt. Deckt ein Test mehrere Schichten ab, bestimmt die im Titel genannte die Hauptschicht; die weiteren kommen als zusätzliche Labels dazu.

Checkliste für künftige Test-Issues:

- Anforderungs-ID(s) referenziert
- Schicht-Label(s) gesetzt
- genau eine Datei bzw. Dateiliste für ein Anliegen + Abnahmekriterium benannt
- Testlauf im Dev Container grün (Commit-SHA verlinkt)
- Standard eingehalten (AssertJ, Backtick-Name mit Anforderungs-ID)
- Mutation-Lauf (nur Kern-Pakete) dokumentiert, wo zutreffend
- kein DC-03-Verstoß (offline)

## Dependency-Set

| Zweck | Artefakt | Version |
|---|---|---|
| Property | `io.kotest:kotest-property` | 6.2.5 |
| Contract | `org.wiremock:wiremock-standalone` | 3.13.2 |
| Wächter | `com.tngtech.archunit:archunit-junit6` | 1.5.0 |
| Mutation | `info.solidsoft.pitest` (Plugin) / pitest | 1.19.0 / 1.25.5 |
| Mutation | `org.pitest:pitest-junit5-plugin` | 1.2.2 |
| Asynchronität | `org.awaitility:awaitility` | verwaltet über `spring-boot-starter-test` |
| AssertJ | `org.assertj:assertj-core` | verwaltet über `spring-boot-starter-test` |
| JSON-Schema | `com.networknt:json-schema-validator` | 3.0.8 |
| HTTP-Client (paperless) | `org.springframework.boot:spring-boot-starter-restclient` | verwaltet über Spring Boot 4.1.1 |
| JSON (Outbox-Metadaten) | `tools.jackson.module:jackson-module-kotlin` | verwaltet über Spring Boot 4.1.1 |

Auswahlbegründungen stehen in `docs/entscheidungen.md`, die Versions-Tabelle in `docs/plan.md`.
