# Plan: `UnboundAir`

Dieser Plan ist der Auftrag für das Projekt: Ziel, feste Entscheidungen und Anforderungen. Der Fortschritt (Meilensteine, Aufgaben) lebt in GitHub – siehe „Arbeit wird in GitHub getrackt". Wie gearbeitet wird, steht in `AGENTS.md`. Fachliche Grundlage ist `docs/protokoll.md` – solange es die noch nicht gibt, `_input/iscan-air-wissen.md`.

Der Dienst verwandelt einen Mustek iScan Air (S400W) in einen „Einlegen und fertig"-Scanner. Die fertigen Dokumente gehen an konfigurierbare Ausgabe-Module; das erste Modul ist paperless-ngx.

## Rahmen

Diese Dateien sind von Anfang an im Repo und werden nur nach Rückfrage geändert – Ausnahme: den Status unten in diesem Plan pflegst du selbst.

- `.gitignore` (enthält `_input/`)
- `AGENTS.md`
- `docs/plan.md` (diese Datei)
- `.opencode/agent/reviewer.md`

`_input/` liegt nur lokal vor und wird nie committet. Inhalte daraus gezielt überführen: Wissen → `docs/`, Testbild → Test-Ressourcen, Python-Referenzcode in Kotlin neu schreiben (nicht 1:1 übersetzen).

## Ziel aus Nutzersicht

1. Scanner einschalten → der Host verbindet sich automatisch mit dem Scanner-WLAN (LED dauerhaft blau).
2. Blatt einlegen → der Dienst scannt automatisch, ohne Knopf.
3. Nächstes Blatt innerhalb des Zeitfensters → gehört zum selben Dokument.
4. Zeitfenster abgelaufen oder Scanner aus → mehrseitiges PDF → Übergabe an die konfigurierten Ausgabe-Module (in v1: paperless-ngx). Weiter geht's dort.

Betrieben wird der Dienst als Container. Perspektivisch kommt eine Web-UI dazu – sie beeinflusst die Architektur, wird in v1 aber nicht gebaut.

## Feste Entscheidungen

- **Stack:** Kotlin + Spring Boot, Build mit Gradle (Kotlin DSL) inklusive Wrapper. Grundsatz: **jeweils die neueste stabile Version**, im Build fest gepinnt – ausdrücklich nicht nur LTS. Stand bei Projektstart (20.09.2026):

  | Baustein | Version | Anmerkung |
  |---|---|---|
  | Java | 26 | höchste Version, die Gradle 9.7.1 ausführen kann. Java 27 ist seit dem 15.09.2026 verfügbar, wird von Gradle aber noch nicht unterstützt. Bewusst **kein** LTS (das wäre 25). |
  | Gradle | 9.7.1 | unterstützt laut Kompatibilitätsmatrix JVM 17–26, JVM 27+ noch nicht |
  | Spring Boot | 4.1.1 | |
  | Kotlin | 2.4.20 | **bewusst neuer** als die von Spring Boot 4.1.1 verwaltete 2.3.21 – siehe Hinweis unten |
  | Apache PDFBox | 3.0.8 | |
  | Spotless-Gradle-Plugin | 8.10.2 | führt den Linter aus, siehe TE-03 |
  | ktlint (über Spotless) | 1.8.0 | der eigentliche Linter |

  Die Obergrenze setzt jeweils der älteste Baustein der Kette: Gradle begrenzt Java, Spring Boot begrenzt Kotlin. Beim Anheben einer Version diese Tabelle mitpflegen.

  **Test-Abhängigkeiten** (alle `testImplementation`; Auswahl begründet in `docs/entscheidungen.md`, Konzept in `docs/teststrategie.md`):

  | Baustein | Version | Anmerkung |
  |---|---|---|
  | kotest-property | 6.2.5 | Property-Tests. jqwik entfällt wegen der Anti-AI-Klausel ab 1.10. |
  | WireMock (standalone) | 3.13.2 | Contract-Tests gegen paperless; 4.x ist noch Beta. |
  | ArchUnit (`archunit-junit6`) | 1.5.0 | Architektur-Wächter, JUnit-6-Unterstützung seit 1.5.0. |
  | gradle-pitest-plugin / pitest | 1.19.0 / 1.25.5 | Mutation, eigener Task, nie Teil von `build`. |
  | pitest-junit5-plugin | 1.2.2 | PIT-Anbindung an JUnit 5/6. |
  | Awaitility | (verwaltet) | über `spring-boot-starter-test` (4.3.0). |
  | AssertJ | (verwaltet) | über `spring-boot-starter-test` (3.27.7), Standard-Assertions. |
  | json-schema-validator (networknt) | 3.0.7 | Contract-JSON-Schema, wird mit der ersten Contract-Testdatei gepinnt. |

  **Warum nicht Java 27:** Gradle 9.7.1 gibt in seiner Kompatibilitätsmatrix ausdrücklich an, JVM 27 und neuer nicht auszuführen. Sobald Gradle nachzieht, ist Java 27 der nächste Schritt – der Grundsatz bleibt „neueste stabile Version".

  **Kotlin wird bewusst hochgezogen:** Spring Boot 4.1.1 verwaltet Kotlin 2.3.21, und dessen Compiler kennt als höchstes Bytecode-Ziel `JVM_25` – mit Java 26 lässt sich damit nicht bauen. Kotlin 2.4.20 kennt `JVM_26`. Deshalb wird die von Spring Boot vorgegebene Kotlin-Version im Build überschrieben. Das ist die einzige Stelle, an der bewusst von Spring Boots verwalteten Versionen abgewichen wird; sie gehört mit Begründung nach `docs/entscheidungen.md` (DO-06). Falls daraus Probleme entstehen, ist der Rückfallweg Java 25 statt 26.
- **Eine Anwendung,** in v1 ohne Web-Oberfläche. Den Kern (Scanner, Verarbeitung, Batch, Ausgabe) so schneiden, dass später eine Web-UI andocken kann, ohne den Kern umzubauen.
- **Abhängigkeiten minimal:** Spring Boot, Apache PDFBox, Spring-eigener HTTP-Client. Bildanalyse mit Java-Bordmitteln (ImageIO). Systemabhängigkeit: `jpegtran` (libjpeg-turbo) als externes Programm.
- Kein SANE, kein AirScan, kein eSCL.
- **Nie neu komprimieren:** Der Scanner liefert JPEG mit Qualität ~50. Zuschnitt und Graustufen verlustfrei per `jpegtran` (`-crop`, `-grayscale`). PDF mit PDFBox, JPEGs per `JPEGFactory` unverändert einbetten, Seitengröße aus Pixeln und DPI. Einzige Ausnahme: optionales `normalize`, Default aus.
- **Modulare Ausgabe:** Fertige Dokumente gehen an austauschbare, konfigurierbare Ausgabe-Module. Erstes und in v1 einziges Modul: paperless-ngx über die REST-API (kein Consume-Ordner).
- **Module per Laufzeit-Auswahl:** Alle Module sind immer registriert; welche aktiv sind, entscheidet die Konfiguration zur Laufzeit. Kein `@ConditionalOnProperty` oder Ähnliches, weil Spring das in GraalVM Native Images nicht unterstützt.
- **Laufzeit:** normale JVM. GraalVM Native Image ist eine spätere Option, nicht v1 – aber nichts einbauen, was sie verbaut.
- Mehrseitige Dokumente über ein Zeitfenster.
- **Drehen und Geraderücken** kommt später in den Dienst, nicht in v1. Bis dahin übernimmt das beim paperless-Modul paperless (OCRmyPDF).
- **Artefakte:** ausführbares JAR + Container-Image (in v1 `linux/arm64`, `linux/amd64` später – siehe CT-01) auf Basis eines OpenJDK-JRE-Image, das die Anforderungen erfüllt. `jpegtran` muss im Image sein: also eine JRE-Variante mit Paketmanager oder die JRE in ein eigenes Debian-Image kopieren.
- **Betrieb als Container** steht fest. Die WLAN-Verbindung zum Scanner hält der Host, der Container braucht Zugriff darauf. Ein konkretes Deployment-Beispiel kommt erst mit Meilenstein 6.

## Anforderungen

Jede Anforderung hat eine feste ID und ein Abnahmekriterium. IDs werden nie umnummeriert; neue Anforderungen bekommen die nächste freie Nummer ihres Bereichs. Issues verweisen auf die IDs, die sie umsetzen.

**Zu den Verweisen auf `_input/`:** Einige Anforderungen nennen als Vorlage Dateien unter `_input/` – den Wissensstand, den Python-Referenzcode, die Testbilder. Dieses Verzeichnis liegt nur lokal vor und wird nie committet. Wer das Repository klont, hat es nicht. Diese Anforderungen sind deshalb für Außenstehende erst dann vollständig nachprüfbar, wenn das Wissen nach `docs/protokoll.md` (DO-01) überführt und die Testbilder als Test-Ressourcen abgelegt sind (TE-02).

### Scanner-Client (SC)

- **SC-01** Befehle, Antworten und Scan-Ablauf exakt laut `_input/iscan-air-wissen.md`.
  *Abnahme:* Ein Test gegen den Fake-Scanner durchläuft Status → DPI → Scan → JPEG-Größe → JPEG-Daten und erhält die Nutzlast bytegleich.
- **SC-02** Eine TCP-Verbindung pro **Vorgang**, nicht pro Befehl. Ein Vorgang ist entweder *eine Statusabfrage* oder *ein kompletter Scan* (`status` → DPI → `scan` → `jpegsize` → `jpegdata`) – der Scan schickt innerhalb seiner Verbindung erneut `status`, das eröffnet keine neue Verbindung. So macht es auch der Referenzcode.
  Pausen: 200 ms vor und nach jedem Senden. Ausnahme: vor dem Lesen der Massendaten (nach `jpegdata`) 500 ms, weil der Referenzcode das am echten Gerät so erprobt hat – siehe `offene-fragen.md`.
  Timeouts: normal 10 s, `jpegsize` 60 s, Daten 30 s pro Lesevorgang.
  *Abnahme:* Test zeigt genau eine neue Verbindung je Vorgang; Pausen und Timeouts stehen zentral an einer Stelle; ein Test mit hängendem Fake-Scanner löst den Timeout aus.
- **SC-03** Antworten per Präfix vergleichen. Die Status- und Bestätigungsantworten sind 11 Byte: Wort + `\x00`-Padding + `H`. Der Präfix-Vergleich darf **keine** bestimmte Länge oder ein bestimmtes Padding voraussetzen – die `version`-Antwort folgt dem Schema nicht (siehe `offene-fragen.md`).
  *Abnahme:* Test mit den echten Füllbytes (z. B. `nopaper\x00\x00\x00H`) erkennt jede Antwort richtig.
- **SC-04** `jpegsize`-Antwort bei Bedarf über mehrere Lesevorgänge lesen, bis 12 Byte da sind.
  *Abnahme:* Test mit geteilter Antwort liefert die richtige Größe.
- **SC-05** Eigene Exceptions: offline, busy, no paper, battery low, protocol error, timeout.
  *Abnahme:* Für jede Exception gibt es einen Test, der sie gezielt auslöst.
- **SC-06** Host und Port des Scanners konfigurierbar, Defaults `192.168.18.33` und `23`. Im Echtbetrieb sind das Konstanten; einstellbar müssen sie sein, damit Tests gegen den Fake-Scanner laufen können.
  *Abnahme:* Test verbindet sich ohne Codeänderung mit dem Fake-Scanner auf einem freien Port; ohne Konfiguration gelten die Defaults oben.
- **SC-07** Firmware-Check vor dem Umstellen der Auflösung: Die DPI nur umstellen, wenn die Zahl nach dem Punkt in der Versionsangabe ≥ 26 ist (Testgerät `NB0a.032` → 32 → umschaltbar). Andernfalls bei 300 dpi bleiben und warnen.
  *Abnahme:* Test mit Version `NB0a.032` stellt um, Test mit einer Version < 26 stellt nicht um und protokolliert eine Warnung.

### Dienst-Loop (DL)

- **DL-01** Status alle `poll-interval` s abfragen (Default 3), neue Verbindung pro Abfrage.
  *Abnahme:* Test mit verkürztem Intervall zählt die Abfragen am Fake-Scanner.
- **DL-02** Scanner nicht erreichbar → langsamer abfragen (`offline-poll-interval`, Default 10) und nur beim Zustandswechsel loggen.
  *Abnahme:* Test: Fake-Scanner offline → längeres Intervall, genau ein Log-Eintrag pro Zustandswechsel.
- **DL-03** `scanready` → Seite scannen → verarbeiten → an den offenen Batch hängen.
  *Abnahme:* Test: Ein eingelegtes Blatt im Fake-Scanner landet ohne weiteres Zutun als Seite im Batch.
- **DL-04** Batch schließen, wenn `batch-timeout` s (vorläufiger Default 20, endgültig nach der Messung mit `measure`) seit dem Ende der letzten Seite vergangen sind **oder** der Scanner offline geht. Das Auto-Off des Scanners nach 5 min ist dabei ein regulärer Auslöser, kein Fehler: Es bedeutet schlicht, dass der Vorgang beendet ist. Pausiert das Polling per DL-06, bemerkt der Dienst das Offline nicht – dann schließt der `batch-timeout` den Batch, der ohnehin vorher greift.
  *Abnahme:* Je ein Test für beide Auslöser.
- **DL-05** Fehlgeschlagene Seite verwerfen und loggen, der Batch bleibt offen.
  *Abnahme:* Test mit Abbruch mitten im Scan: Seite fehlt, Batch läuft weiter, Log-Eintrag vorhanden.
- **DL-06** Leerlauf-Verhalten konfigurierbar (z. B. Abfragen nach `idle-minutes` verlangsamen oder pausieren), Default aus, bis Messwerte vorliegen.
  *Abnahme:* Test: Mit Einstellung wird langsamer bzw. gar nicht mehr abgefragt, ohne Einstellung ändert sich nichts.
- **DL-07** Beim Beenden (SIGTERM/SIGINT): offenen Batch noch abschließen und an die Outbox übergeben.
  *Abnahme:* Test: Beenden mit offenem Batch → das PDF liegt in der Outbox.

### Seitenverarbeitung (SV)

- **SV-01** Auto-Zuschnitt nach `_input/reference/autocrop_reference.py`: Papier vor schwarzem Hintergrund finden, Ursprung nach innen auf die iMCU-Grenze runden (aus dem Chroma-Subsampling), `jpegtran -crop`.
  *Abnahme:* Zwei echte Testbilder, zwei Fälle:
  - `envelope_dl_300dpi_raw.jpg` (roh 1776 × 2769) wird auf ca. 1216 × 2494 px zugeschnitten, Luma identisch mit dem Original-Ausschnitt.
  - `din_a4_300dpi_raw.jpg` (roh 2464 × 3425) hat keinen schwarzen Rand – jede Zeile und Spalte ist hell. Hier darf der Zuschnitt **nichts** abschneiden: die Ausgabe ist bytegleich zur Eingabe.

  Der zweite Fall ist kein Sonderfall, sondern normales Geräteverhalten: Der Scanner erzeugt nicht immer einen Rand. Darauf, dass ein Rand da ist, darf sich der Algorithmus nicht verlassen.
- **SV-02** Plausibilitätsprüfung (z. B. Papierfläche < 10 % des Bildes oder absurdes Seitenverhältnis) → Seite unbeschnitten übernehmen und warnen.
  *Abnahme:* Ein dunkles Testbild bleibt unbeschnitten, im Log steht eine Warnung.
- **SV-03** `color-mode`: `gray` (Default, `jpegtran -grayscale`) oder `color`.
  *Abnahme:* Bei `gray` hat das Ergebnis genau eine Komponente (Luma) mit unveränderten Luma-Werten, bei `color` bleibt es farbig.
- **SV-04** `normalize` optional (Default aus): einziger Pfad mit Neukomprimierung, in der Doku klar als verlustbehaftet markiert.
  *Abnahme:* Ohne `normalize` ist das Ergebnis bytegleich zur `jpegtran`-Ausgabe; mit `normalize` gibt es einen eigenen Test und den Hinweis in der Doku.
- **SV-05** Seitengröße im PDF = Pixel ÷ DPI. Keine Umrechnung auf Normformate.
  *Abnahme:* Test: Die PDF-Seite misst Pixel ÷ DPI × 72 pt (± 1 pt).
- **SV-06** `keep-raw` (Debug): Roh-JPEGs zusätzlich ablegen.
  *Abnahme:* Test: Mit `keep-raw` liegt die Rohdatei zusätzlich vor, ohne nicht.
- **SV-07** Die Verarbeitung als Kette einzelner Schritte bauen (Zuschnitt, Graustufen, …), damit Drehen und Geraderücken später als weitere Schritte dazukommen, ohne den Rest umzubauen.
  *Abnahme:* Test hängt einen Dummy-Schritt in die Kette, ohne bestehende Schritte zu ändern.

### PDF & Ausgabe-Module (AU)

- **AU-01** PDFBox, mehrseitig, JPEGs unverändert eingebettet, DPI explizit.
  *Abnahme:* Test: 3 Seiten → PDF mit 3 Seiten; jedes eingebettete Bild ist bytegleich zu seiner Eingabedatei.
- **AU-02** Modul-Schnittstelle: Ein Ausgabe-Modul bekommt ein fertiges Dokument (PDF plus Metadaten wie Scan-Zeitpunkt und Seitenzahl) und meldet Erfolg oder Fehler zurück. Neue Module lassen sich ergänzen, ohne den Kern zu ändern.
  *Abnahme:* Ein Modul, das nur im Test existiert, lässt sich ohne Änderung am Kern einhängen und empfängt Dokument und Metadaten.
- **AU-03** Welche Module aktiv sind, steht in der Property `unboundair.output.modules` (Env-Var `UNBOUNDAIR_OUTPUT_MODULES`) als Komma-Liste, z. B. `paperless`, und wird zur Laufzeit ausgewertet. Jedes Modul hat eigene Einstellungen unter `unboundair.output.<modul>.*`, beim paperless-Modul also `unboundair.output.paperless.*`.
  *Abnahme:* Test: Nur konfigurierte Module erhalten Dokumente. Im Code gibt es kein `@ConditionalOnProperty` o. Ä.
- **AU-04** Outbox (für alle Module gleich): Dokument erst lokal persistieren, dann ans Modul übergeben, erst nach Erfolg löschen. Retry mit exponentiellem Backoff, überlebt Neustarts.
  *Abnahme:* Test: Modul schlägt fehl → Datei bleibt, Wiederholungen mit wachsendem Abstand; nach einem Neustart wird sie zugestellt und dann gelöscht.
- **AU-05** Modul `paperless-ngx`: `POST /api/documents/post_document/`, Multipart-Feld `document`, Header `Authorization: Token …`, optionale Tag-IDs (Feld `tags` mehrfach), Dateiname `scan-YYYYMMDD-HHMMSS.pdf`. Task-UUID aus der Antwort loggen. Token aus Env-Var oder Datei, wobei der Dateipfad als Env-Var kommt.
  *Abnahme:* Test gegen Mock-HTTP prüft Pfad, Feld, Header, Tags und Dateinamen; die Task-UUID steht im Log; Token aus Variable und aus Datei funktionieren beide.
- **AU-06** In v1 nur das paperless-Modul implementieren.
  *Abnahme:* Außer paperless-ngx gibt es kein produktives Ausgabe-Modul.

### Konfiguration & Logging (KL)

- **KL-01** Spring-Boot-Properties unter `unboundair.*` (klein, mit Punkten), per Umgebungsvariable setzbar (groß, mit Unterstrichen) – die bei Spring übliche Schreibweise. Achtung bei der Umrechnung: Jeder Punkt wird zum Unterstrich, ein Bindestrich im Namen entfällt ersatzlos.

  | Property | Umgebungsvariable |
  |---|---|
  | `unboundair.poll-interval` | `UNBOUNDAIR_POLLINTERVAL` |
  | `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` |
  | `unboundair.outbox.path` | `UNBOUNDAIR_OUTBOX_PATH` |
  | `unboundair.output.paperless.token-file` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` |

  Alle Defaults zentral an einer Stelle und in der Doku.
  *Abnahme:* Test: Eine Umgebungsvariable überschreibt den Default. Jede Einstellung steht mit ihrem Default in der Doku.
- **KL-02** Logs auf stdout (journald-freundlich). Pro Seite: Scan-Dauer, Übertragungsdauer, Größe, Maße in mm nach Zuschnitt.
  *Abnahme:* Test: Der Log-Eintrag einer Seite enthält alle vier Werte.

### Befehle (BE)

Unterbefehle der Anwendung (Umsetzung entscheidest du, z. B. Startskript `unboundair` oder `java -jar`):

- **BE-01** `status` – Status und Firmware-Version.
  *Abnahme:* Gegen den Fake-Scanner werden Status und `NB0a.032` ausgegeben.
- **BE-02** `scan [--dpi 300|600] [--out DATEI]` – eine Seite, roh und beschnitten speichern.
  *Abnahme:* Gegen den Fake-Scanner entstehen die Roh- und die beschnittene Datei.
- **BE-03** `crop IN OUT` – Zuschnitt einer vorhandenen Datei.
  *Abnahme:* Das echte Testbild ergibt dasselbe Ergebnis wie bei SV-01.
- **BE-04** `measure` – Messmodus wie `_input/reference/iscan_autoscan_test.py`: automatisch scannen ohne Ausgabe an Module, misst Seitenabstände, `devbusy`, Auto-Off und Doppelscans, Zusammenfassung am Ende.
  *Abnahme:* Gegen einen Fake-Scanner mit mehreren Seiten, `devbusy` und Offline enthält die Zusammenfassung Seitenzahl, Abstände, `devbusy`-Anzahl und Offline-Zeitpunkt; kein Dokument geht an ein Modul.
- **BE-05** `run` – der Dienst.
  *Abnahme:* siehe „Ergebnis", Punkt 4.

### Container (CT)

- **CT-01** Container-Image auf Basis eines OpenJDK-JRE-Image, das die Anforderungen erfüllt, mit `jpegtran`. **In v1 nur `linux/arm64`** – das ist die Architektur der Entwicklungsumgebung, nur dort kann der Build verifiziert werden. `linux/amd64` kommt später; das Dockerfile wird so geschrieben, dass es keine Architektur fest verdrahtet.
  *Abnahme:* Das Image baut für `linux/arm64`; im Container laufen `jpegtran -version` und `status` gegen den Fake-Scanner. Im Dockerfile steht keine feste Architektur.

### Dev Container (DC)

- **DC-01** `.devcontainer/` mit allem, was Build und Tests brauchen: JDK passend zur Laufzeit, Gradle über den Wrapper, `jpegtran` (libjpeg-turbo) – dieselben Systemabhängigkeiten wie im Runtime-Image.
  *Abnahme:* Im Dev Container liefern `java -version` und `jpegtran -version` Ausgaben, passend zum Runtime-Image.
- **DC-02** Auf dem Host muss außer Container-Runtime und Dev-Container-Tooling nichts installiert sein.
  *Abnahme:* `docs/entwicklung.md` nennt keine weiteren Voraussetzungen für den Host.
- **DC-03** `./gradlew test` läuft im Dev Container komplett durch, ohne Netzwerkzugriff auf echte Geräte oder Dienste.
  *Abnahme:* Testlauf im Dev Container ist grün.

Hinweis: Wie die Tests im Dev Container gestartet werden, hängt von der Umgebung ab, in der du arbeitest, und gehört nicht ins Repo. Kannst du sie nicht selbst im Dev Container starten: sag es mir – lass sie nicht stillschweigend woanders laufen.

**Aktuelle Lage:** Der Dev Container läuft lokal – es gibt keinen Umweg mehr über einen anderen Rechner. Ein Wrapper außerhalb des Repos startet ihn, das Repo wird per `docker cp` hineingespiegelt, und `./gradlew build` läuft darin. Der Ablauf steht in `docs/entwicklung.md`.

Eine Aufgabe gilt erst als abgenommen, wenn ihr Testergebnis im zugehörigen Issue dokumentiert ist (Test-Checkliste abgehakt, Lauf im Dev Container grün mit Commit-SHA). Aufgaben, die geschrieben, aber noch nicht ausgeführt wurden, gelten als „nicht verifiziert" und werden nicht abgehakt.

### Tests (TE)

Die meisten Tests ergeben sich aus den Abnahmekriterien oben. Zusätzlich:

- **TE-01** Fake-Scanner nach `_input/reference/fake_scanner.py` in Kotlin als TCP-Server im Test (bildet die echten Füllbytes, geteilte `jpegsize`-Antwort, `devbusy`, Offline und `battlow` nach). `battlow` kennt die Referenz nicht, wird aber für SC-05 gebraucht.
  *Abnahme:* Jedes dieser fünf Verhalten lässt sich im Test gezielt einschalten.
- **TE-02** Testbilder: die echten Testbilder aus `_input/fixtures/` als Test-Ressource plus synthetische Fälle: A4 ohne Schwarz oben und seitlich, nur Streifen unten; dunkles Bild.
  *Abnahme:* Alle Testbilder liegen als Test-Ressourcen vor und werden in den SV-Tests genutzt.
- **TE-03** Linting mit ktlint, ausgeführt über das Spotless-Gradle-Plugin (entschieden, siehe „Entschieden – nicht mehr offen").
  *Abnahme:* Der Linter läuft im Build mit (`spotlessCheck` hängt an `check`) und meldet nichts.

### Doku (DO) – `docs/`, Deutsch als führende Fassung

- **DO-01** `protokoll.md` – aus `_input/iscan-air-wissen.md`, mit Herkunftsmarkierungen.
  *Abnahme:* Jede Aussage trägt ihre Herkunftsmarkierung.
- **DO-02** `hardware.md` – Gerät, Messwerte, bekannte Eigenheiten.
  *Abnahme:* Enthält die Messwerte aus dem Wissensstand; neue Messwerte aus `measure` werden ergänzt.
- **DO-03** `betrieb.md` – Betrieb als Container, unabhängig von einer bestimmten Container-Runtime beschrieben:
  - **Host-Voraussetzungen:** Die WLAN-Verbindung zum Scanner hält der Host. NetworkManager-Profil fürs Scanner-WLAN (an `wlan0` gebunden, `connection.autoconnect yes`, `connection.autoconnect-retries 0`, `ipv4.never-default yes`), nftables auf `wlan0` (eingehend nur established/related + DHCP, ausgehend nur `192.168.18.33:23` + DHCP, kein Forwarding).
  - **Netzwerk:** Der Container muss `192.168.18.33:23` über das WLAN des Hosts erreichen (z. B. Host-Netzwerk).
  - **Persistenz:** Die Outbox liegt auf einem persistenten Volume, sonst gehen ungesendete Dokumente beim Neustart verloren.
  - **Konfiguration & Secrets:** Einstellungen per `UNBOUNDAIR_…`-Umgebungsvariablen (Schreibweise siehe KL-01), paperless-Token als eingebundene Datei über `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE`.
  - **Beenden:** Beim Stoppen schließt der Dienst den offenen Batch ab. Der Stop-Timeout der Container-Runtime muss dafür reichen, ein laufender Scan kann bis zu 60 s dauern.
  - **Logs, Neustart, Update:** Logs auf stdout, Neustart-Verhalten, Update eines laufenden Containers.

  *Abnahme:* Alle sechs Unterpunkte sind beschrieben, ohne eine bestimmte Container-Runtime vorauszusetzen.
- **DO-04** `entwicklung.md` – Dev Container, Build, Tests.
  *Abnahme:* Wer nur die Datei liest, bekommt `./gradlew test` im Dev Container grün.
- **DO-05** `ausgabe-module.md` – Modul-Schnittstelle, paperless-Modul, Anleitung für neue Module.
  *Abnahme:* Die Anleitung reicht, um das Test-Modul aus AU-02 nachzubauen.
- **DO-06** `entscheidungen.md` – die festen Entscheidungen mit Begründung.
  *Abnahme:* Jede feste Entscheidung aus diesem Plan steht mit Begründung drin.
- **DO-07** `offene-fragen.md` – aus dem Wissensstand, wird mit Messwerten fortgeschrieben. Die Datei entsteht **bereits in Meilenstein 1** und wird danach laufend fortgeschrieben, weil die Leitplanke „Nichts am Protokoll erfinden – Offenes gehört in `docs/offene-fragen.md`" ab der ersten Codezeile gilt.
  *Abnahme:* Alle offenen Punkte aus dem Wissensstand sind mit Status aufgeführt.
- **DO-08** `README.md` im Wurzelverzeichnis – Einstieg und Wegweiser. Entsteht **bereits in Meilenstein 1**, damit von Anfang an erkennbar ist, welche Datei wofür da ist; in Meilenstein 5 kommt der Schnellstart dazu.
  *Abnahme:* Erklärt, was `UnboundAir` ist, nennt den Aufbaustand und verweist auf jede Datei in `docs/` mit einem Satz, wofür sie da ist. Ab Meilenstein 5 zusätzlich: Schnellstart.

## Arbeit wird in GitHub getrackt

Der Fortschritt lebt nicht mehr in dieser Datei, sondern in GitHub: [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) und [Issues](https://github.com/digiwomb-dev/UnboundAir/issues). Jeder Meilenstein aus „Umfang von v1" ist ein Milestone, jede Aufgabe ein Issue – Impl+Test-Paare als Eltern-Issue (Typ `Task`) mit zwei Sub-Issues. Welche Anforderungen ein Meilenstein umsetzt, steht in seiner Milestone-Beschreibung. Labels, Milestones und die Issue-Vorlage sind auf Englisch – wie Code, Commits und PR-Titel; nur die Doku ist Deutsch.

Der Fortschritt von Meilenstein 2 liegt ab jetzt im Milestone [`2`](https://github.com/digiwomb-dev/UnboundAir/milestones/2).

## Offene Entscheidungen – nicht vorwegnehmen, fragen

Hier stehen nur Punkte, die **eine Entscheidung** brauchen. Was sich dagegen nur **am Gerät klären** lässt, steht in `docs/offene-fragen.md` – dort ist die Abgrenzung erklärt. Zweistufige Punkte (erst messen, dann entscheiden) stehen in beiden Listen und verweisen aufeinander.

- **Deployment:** Ziel-Host und konkretes Deployment-Beispiel. Dass als Container betrieben wird, steht fest.
- **CI:** Tests und Image-Build. Welches CI-System, ist egal – wird erst mit Meilenstein 6 festgelegt.
- **Web-UI:** Umfang und Technik – kommt perspektivisch, nicht in v1.
- **Drehen und Geraderücken (kommt später):** Drehen um 90/180/270° geht mit `jpegtran -rotate` ohne Qualitätsverlust. Geraderücken um kleine Winkel geht nur mit Neukomprimierung – das widerspricht „Nie neu komprimieren" und muss vorher entschieden werden. Offen ist auch, wie die Leserichtung erkannt wird.
- **GraalVM Native Image:** später prüfen, vor allem ob ImageIO/AWT und PDFBox darin laufen.
- **Mehrere Ausgabe-Module gleichzeitig** (ein Dokument an mehrere Ziele) oder immer genau eins? Die Konfiguration nimmt bereits eine Komma-Liste entgegen (AU-03), damit diese Entscheidung offen bleibt; in v1 ist nur ein Wert sinnvoll.
- **Englische Doku:** wie die Übersetzung ins Repo kommt und mit der deutschen Fassung synchron bleibt (Struktur, Werkzeug, Ablauf).
- **Defaults** für `poll-interval`, `batch-timeout` und Leerlauf – nach Messung mit `measure` (Messgrundlagen: OF-01 bis OF-03). Bis dahin gelten die vorläufigen Defaults aus DL-01/DL-04 (3 s bzw. 20 s).
- **Seitengrößen-Abweichung:** ob der Dienst die Abweichung ausgleicht oder die Pixelmaße unverändert übernimmt – erst nach der Messung zu entscheiden (OF-05).
- **`normalize`:** was es genau tun soll – Kontrast strecken, Weißpunkt setzen, etwas anderes – und mit welchem Werkzeug. ImageMagick wäre eine zusätzliche Systemabhängigkeit und stünde gegen „Abhängigkeiten minimal". Bis zur Entscheidung wird SV-04 nicht gebaut.
- **`linux/amd64`-Image:** wann es dazukommt und wie es verifiziert wird (siehe CT-01).

## Entschieden – nicht mehr offen

Punkte, die zu Projektbeginn geklärt wurden. Die Begründungen gehören nach DO-06 in `docs/entscheidungen.md`.

- **Lizenz:** Apache-2.0. Wie MIT freizügig, aber mit ausdrücklicher Patentklausel – sinnvoll, weil hier ein Hersteller-Protokoll nachgebaut wird. Herkunft wird dokumentiert: s400w ist CC0 (kein Code übernommen, nur Protokollwissen), AirScan als Quelle genannt, kein Hersteller-Code im Repo.
- **Linter (TE-03):** ktlint als Regelwerk, ausgeführt über das **Spotless**-Gradle-Plugin. Reine Formatierung, kaum Konfiguration, wenig Rauschen – detekt würde mehr Feinjustierung verlangen, ohne hier mehr zu bringen.

  Der Umweg über Spotless ist nicht Geschmackssache, sondern nötig: Das ktlint-Gradle-Plugin scheiterte mit `Extensions storage is not registered`. Ursache ist eine Kette aus drei Gliedern – `spring-boot-dependencies` importiert das `kotlin-bom`, dieses verwaltet auch `kotlin-compiler-embeddable`, und `io.spring.dependency-management` wendet das auf **alle** Konfigurationen an, also auch auf die des Linters. Dadurch bekommt ktlint statt seines eigenen Compilers (2.1.0) den des Projekts (2.4.20) untergeschoben und stürzt ab. Spotless löst seine Werkzeuge über `detachedConfiguration` auf, die von `configurations.all {}` nicht erfasst wird – damit greift die Überschreibung dort nicht.

  Erschwerend: ktlint ist mit Kotlin 2.4 ohnehin nicht kompatibel (ktlint-Issue 3289, gemeldet von einem JetBrains-Compiler-Entwickler); der Fix existiert bisher nur in ktlint 2.0.0-ALPHA. Der Linter parst deshalb bewusst mit einem älteren Compiler als dem, mit dem übersetzt wird – für Formatierungsregeln genügt das. Siehe OF-11.
- **DPI-Quelle (SV-05):** Maßgeblich ist die **befohlene** Auflösung (wir setzen `dpi300`/`dpi600` selbst). Der JPEG-Header wird zusätzlich gelesen; weicht er ab, wird **gewarnt, nicht abgebrochen**. Grund: Laut offener Frage 5 stimmt die physische Größe ohnehin nicht, der Header ist also nicht vertrauenswürdiger als unser eigener Befehl – eine Abweichung ist aber ein wertvoller Hinweis.
- **Modul-Auswahl (AU-03):** Umgebungsvariable `UNBOUNDAIR_OUTPUT_MODULES` als Komma-Liste, in v1 mit dem Wert `paperless`. Eine Liste kostet jetzt nichts und nimmt die offene Frage „mehrere Module gleichzeitig" nicht vorweg.
- **Outbox (AU-04):** Ablage unter `unboundair.outbox.path`, Default `/var/lib/unboundair/outbox`; je Dokument ein Unterordner mit `document.pdf` und `metadata.json` (Metadaten müssen mitpersistiert werden, sonst überleben sie den Neustart nicht). Backoff: Start 30 s, Faktor 2, Deckel 1 h, unbegrenzte Versuche. Nach erfolgreicher Zustellung wird der Ordner gelöscht.
- **Dateiname und paperless-Felder (AU-05):** Zeitstempel = Beginn der **ersten Seite** des Batches, in der **lokalen Zeitzone** des Containers (über `TZ` steuerbar) – der Name ist für Menschen gedacht, nicht für Maschinen. `tags`, `correspondent` und `document_type` sind optional als numerische IDs konfigurierbar. `title` und `created` werden **nicht** gesetzt, weil paperless sie sonst schlechter ableitet als selbst bestimmt.
- **Doppelscan-Schutz:** in v1 weggelassen.

## Ergebnis: Was nach diesem Auftrag erledigt ist (v1)

Der Auftrag ist fertig, wenn alles hier stimmt – vorher nicht:

1. **Projekt:** Gradle-Projekt mit Kotlin und Spring Boot, Wrapper, fest gepinnte Versionen, Linter ohne Befunde. `_input/` steht in `.gitignore` und wurde nie committet.
2. **Dev Container:** Repo im Dev Container öffnen → `./gradlew test` läuft komplett grün, ohne Zugriff auf echte Geräte oder Dienste.
3. **Befehle:** `status`, `scan`, `measure` und `run` funktionieren gegen den Fake-Scanner; `crop` arbeitet auf einer vorhandenen Datei und braucht keinen Scanner.
4. **Dienst:** `run` gegen Fake-Scanner und Mock-paperless: 3 Seiten → 1 PDF mit 3 Seiten in korrekter Größe, ans paperless-Modul übergeben und hochgeladen. Scanner offline schließt den Batch. Outbox-Retry funktioniert nach Neustart.
5. **Zuschnitt:** Das Kuvert-Testbild wird verlustfrei auf ca. 1216 × 2494 px zugeschnitten (Luma identisch); das A4-Testbild ohne schwarzen Rand bleibt unverändert (bytegleich).
6. **Container:** Image baut für `arm64` (amd64 später), `jpegtran` ist darin verfügbar, `status` läuft im Container gegen den Fake-Scanner.
7. **Doku:** alle Dateien in `docs/` vollständig auf Deutsch, `betrieb.md` beschreibt den Container-Betrieb, README mit Schnellstart (DO-08).
8. **Git:** alles in kleinen Commits nach Conventional Commits.
9. **Anforderungen:** Für jede ID oben ist das Abnahmekriterium erfüllt – ausgenommen die unter „Bewusst noch nicht erledigt" aufgeführten.

**Bewusst noch nicht erledigt:** Test am echten Scanner, Web-UI, Drehen und Geraderücken, **SV-04 (`normalize`)**, weitere Ausgabe-Module, Native Image, CI, Deployment-Beispiel, englische Doku.

**Umfang von v1:** Die Meilensteine 1 bis 5. Meilenstein 6 (Deployment-Beispiel und CI) gehört ausdrücklich nicht dazu.

## Übergabe

Deine letzte Antwort fasst zusammen, was erledigt ist, und nennt mir als ersten Schritt den Test am echten Gerät:

1. Scanner einschalten und warten, bis die LED blau blinkt.
2. Den Rechner, auf dem der Container läuft, ins Scanner-WLAN hängen.
3. Das Container-Image mit dem Befehl `status` starten – gib mir dafür den fertigen Befehl.
4. Erwartet: Status `nopaper` und Firmware `NB0a.032`. Danach geht's mit `measure` weiter.
