# Teststrategie

Wie `UnboundAir` getestet wird: die Testschichten, die Werkzeuge je Schicht und die Gründe dafür. Die Strategie ist die fachliche Grundlage für alle Test-Issues; die einzelnen Entscheidungen dahinter stehen mit Begründung in `docs/entscheidungen.md`, die fest gepinnten Versionen in `docs/plan.md`.

## Grundsätze

- **Offline (DC-03):** `./gradlew test` läuft im Dev Container komplett ohne Zugriff auf echte Geräte oder fremde Dienste. Der Scanner wird durch einen Fake-Scanner (TCP-Server im Test) ersetzt, paperless-ngx durch einen Mock. Eine Netzwerkverbindung braucht nur, wer zum ersten Mal baut (Gradle-Wrapper, Abhängigkeiten) — danach läuft der Testlauf mit warmem Cache offline.
- **Keine Testcontainers.** Test-Dependencies gibt es ausschließlich im Test-Scope; der Runtime-Classpath bleibt unverändert.
- **Versionen fest gepinnt** (Grundsatz „neueste stabile", siehe `docs/plan.md`).
- **Standard-Assertions: AssertJ.** Testnamen in Backticks und mit der umgesetzten Anforderungs-ID (z. B. `SC-01 …`).
- **Ein File pro AI-Lauf** (zweistufiges GitHub-Tracking, siehe unten).

## Die Testschichten

Die Labels im GitHub-Tracking sind deckungsgleich mit den Schichten: `unit`, `property`, `slice`, `integration`, `contract`, `e2e`, `golden-master`, `mutation`.

### 1. Unit (`unit`)

Reine JVM-Tests für einzelne Einheiten ohne Spring-Kontext, ohne Netz und ohne externes Programm. Assertions mit **AssertJ**.

- **Warum:** schnell, deterministisch, die breite Basis. Der Kern (Scanner-Client, Verarbeitung, Batch, Ausgabe) besteht aus kleinen, testbaren Einheiten.
- **Abgrenzung:** alles, was einen Spring-Kontext, einen TCP-Socket oder `jpegtran` braucht, gehört in eine tiefere Schicht.

### 2. Property (`property`)

Eigenschaftsbasierte Tests mit **kotest-property** (`forAll`/`checkAll`), aufgerufen aus gewöhnlichen JUnit-Jupiter-`@Test`-Methoden. kotest-property registriert keine eigene Engine, deshalb läuft es neben den übrigen Tests auf derselben JUnit Platform.

- **Warum:** deckt Randfälle ab, die handgeschriebene Beispiele übersehen — wichtig für den Auto-Zuschnitt (SV-01/SV-02), wo beliebige Bildgeometrien eintreffen.
- **Achtung:** `forAll`/`checkAll` geben einen Rückgabewert zurück; die `@Test`-Methode muss deshalb einen Block-Body haben (kein `= runBlocking { … }`-Ausdruckskörper), sonst verweigert JUnit die Ausführung.
- **Warum nicht jqwik:** jqwik ab 1.10 verbietet die Nutzung durch KI-Coding-Agents — dieses Projekt arbeitet mit KI-Agenten. Begründung in `docs/entscheidungen.md`.

### 3. Slice (`slice`)

Gezielte Spring-Boot-Slices statt voller Kontext. Konkret:

- **`@JsonTest`** für JSON-Serialisierung/Deserialisierung (z. B. `metadata.json` der Outbox, AU-04).
- **Config-Binding:** Tests für `@ConfigurationProperties` unter `unboundair.*` (KL-01) — jede Einstellung mit Default, Umgebungsvariable überschreibt.
- **Context-Smoke:** ein einziger Test, der den ApplicationContext hochzieht und bestätigt, dass die Konfiguration auflösbar ist (fängt Verdrahtungsfehler, ohne echtes Verhalten zu prüfen).
- **`MockRestServiceServer`** für den paperless-Client gegen einen gemockten `RestClient` (AU-05) — ohne HTTP-Server, wenn nur die Request-Seite zählt.

- **Warum:** prüft die Spring-Verdrahtung billig und gezielt, wo ein voller Kontext unnötig teuer wäre.
- **Abgrenzung:** `@WebMvcTest` und `@DataJpaTest` entfallen (siehe „Was fehlt und warum").

### 4. Integration (`integration`)

Zusammenwirken mehrerer echter Bausteine, aber ohne echte Geräte/Dienste:

- **FakeScanner-TCP** (TE-01): der Scanner-Client spricht über einen echten TCP-Socket mit dem im Test laufenden Fake-Scanner (Füllbytes, geteilte `jpegsize`-Antwort, `devbusy`, Offline, `battlow`).
- **`jpegtran`**: Zuschnitt/Graustufen laufen über das echte externe Programm (SV-01, SV-03) — dieselbe Systemabhängigkeit wie im Laufzeit-Image.
- **Awaitility + injizierbare Clock**: asynchrone Abläufe (Dienst-Loop DL-01 bis DL-07, Outbox-Retry AU-04) werden mit Awaitility synchronisiert, Zeit mit einer injizierbaren Clock gesteuert statt `Thread.sleep`.

- **Warum:** die riskantesten Stellen des Dienstes sind die Protokoll-/Zeit- und Prozessgrenzen; genau die werden hier mit den echten Mechanismen (Socket, externes Programm) geprüft.

### 5. Contract (`contract`)

Schnittstellenverträge nach außen:

- **WireMock** als paperless-ngx-Ersatz (AU-05): Pfad `/api/documents/post_document/`, Multipart-Feld `document`, Header `Authorization: Token …`, optionale Tag-Felder, Dateiname `scan-YYYYMMDD-HHMMSS.pdf`, Task-UUID aus der Antwort.
- **JSON-Schema**: Antworten werden gegen ein Schema validiert (Werkzeug `com.networknt:json-schema-validator`, wird mit der ersten Contract-Testdatei fest gepinnt).
- **FakeScanner-Transkript** (SC-01): ein aufgezeichnetes Sitzungs-Transkript (Befehle/Antworten) dient als Golden-Source, gegen die der Client bytegenau geprüft wird.

- **Warum:** wir sind Consumer der paperless-API; der Vertrag sichert, dass das Modul auch bei API-Änderungen auf unserer Seite sofort bricht — ohne echtes paperless.
- **Abgrenzung:** Spring Cloud Contract entfällt (wir sind reiner Consumer, siehe unten).

### 6. E2E offline (`e2e`)

Der ganze Dienst offline: `run` gegen Fake-Scanner **und** WireMock-paperless. Drei Seiten → ein dreiseitiges PDF, an das Modul übergeben und hochgeladen; Scanner offline schließt den Batch; Outbox-Retry nach Neustart. Kein echtes Gerät, kein echtes paperless.

- **Warum:** bestätigt, dass die Bausteine im Zusammenspiel das Ergebnis aus `docs/plan.md` („Ergebnis", Punkt 4) liefern.

### 7. Golden Master (`golden-master`)

Byte-genaue Referenzartefakte unter `golden/` mit einem **sha256-Manifest**. Ergebnisdateien (zugeschnittene JPEGs, PDFs) werden gegen den gespeicherten Stand verglichen.

- **Warum:** schützt vor stillen Regressionen, wo „ungefähr richtig" nicht reicht (verlustfreier Zuschnitt, JPEG-Einbettung per `JPEGFactory`).
- **PDF-Determinismus:** PDF-Metadaten (CreationDate) stammen aus der **injizierbaren Clock** (Scan-Zeitpunkt = Beginn der ersten Seite). Tests pinnen die Clock → bytegleiche PDFs; die Produktion behält echte Zeitstempel. Begründung in `docs/entscheidungen.md`.

### 8. Mutation (`mutation`)

**PIT** (pitest 1.25.5) über den eigenen Gradle-Task `pitest`, Ziel sind die Kern-Pakete (`scanner`, `image`, `processing`). Der Task ist **nie Teil von `build`/`check`** und läuft nur auf ausdrücklichen Aufruf.

- **Warum:** Mutation deckt Lücken in der Assertion-Qualität auf, die Coverage allein nicht zeigt.
- **Grenzen (gemessen, Spike B):** PIT funktioniert auf JUnit Platform 6 (das bekannte Problem 0 %-Coverage ist mit pitest 1.25.5 behoben). Die zeitgesteuerten Scanner-Tests machen Läufe über den ganzen Kern langsam; deshalb `timeoutConstInMillis` erhöht. Zahlen und Entscheidung in `docs/entscheidungen.md`.

### Wächter (Guard)

Ergänzend zu den Schichten, als eigene Datei je Konzern:

- **ArchUnit** (`archunit-junit6`): Architekturregeln — Paketabhängigkeiten zwischen `scanner`, `processing`, `cli`, `outbox`/`ausgabe`; **kein `@ConditionalOnProperty`** im Code (AU-03).
- **Konventionstests:** alle Defaults zentral und in der Doku (KL-01), Exception-Hierarchie (SC-05), Test-Benennung.

## Was fehlt und warum

- **`@WebMvcTest` / `@DataJpaTest`:** Es gibt keine Web-Oberfläche (v1) und keine Datenbank. Beide Slices hätten nichts zu testen.
- **Spring Cloud Contract:** Wir sind Consumer der paperless-API, kein Producer, der Verträge veröffentlicht. Der Vertrag wird deshalb mit WireMock + JSON-Schema auf Consumer-Seite gesichert.
- **Testcontainers:** DC-03 verlangt offline grüne Tests; Testcontainers würde eine Container-Laufzeit im Test und echte Dienste (paperless, später ggf. DB) voraussetzen. Dazu „Abhängigkeiten minimal" und die GraalVM-Native-Image-Option.
- **jqwik:** Anti-AI-Klausel (siehe Property-Schicht und `docs/entscheidungen.md`).

## GitHub-Tracking-Modell

Testarbeit lebt in GitHub (Session 1, Option A), zweistufig: Ein Impl+Test-Paar ist ein Eltern-Issue (Typ `Task`) mit zwei Sub-Issues `feat(…)` und `test(…)`. Der `test(…)`-Sub-Issue trägt das Schicht-Label und die feste Checkliste aus `.github/ISSUE_TEMPLATE/testaufgabe.yml`. Ein AI-Lauf bearbeitet genau eine Datei; ein Mensch darf ein Anliegen mit Dateiliste übernehmen.

Checkliste für künftige Test-Issues:

- Anforderungs-ID(s) referenziert
- Schicht-Label(s) gesetzt
- genau eine Datei (AI-Lauf) bzw. Dateiliste (Mensch) + Abnahmekriterium benannt
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
| JSON-Schema | `com.networknt:json-schema-validator` | 3.0.7, wird mit der ersten Contract-Testdatei gepinnt |

Auswahlbegründungen stehen in `docs/entscheidungen.md`, die Versions-Tabelle in `docs/plan.md`.
