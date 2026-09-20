# Meilenstein 1 – Grundgerüst

Gradle mit Kotlin DSL und Wrapper, Spring Boot, Linter, JUnit, Dev Container, Scanner-Client, Fake-Scanner, `offene-fragen.md`, Befehle `status` und `scan` (vorerst nur Roh-Datei).

**Anforderungen:** SC-01–SC-07, DC-01–DC-03, TE-01, TE-03, BE-01, BE-02 (Teil: nur Roh-Datei, Zuschnitt folgt in Meilenstein 2), DO-07, DO-08.

**Status:** in Arbeit.

## Teststand

Was zuletzt tatsächlich ausgeführt wurde. Eine Aufgabe gilt erst als abgenommen, wenn ihr Ergebnis hier steht – zum Verfahren siehe `entwicklung.md`, Abschnitt „Wie getestet wird, solange keine Runtime da ist".

| Was | Stand |
|---|---|
| Zuletzt getesteter Commit | *(noch keiner)* |
| Ergebnis | *(ausstehend)* |
| Als Nächstes zu prüfen | Testpunkt 1 |

## Testpunkte

Der Meilenstein ist in vier Testpunkte geschnitten, damit ein Fehlschlag klein und zuordenbar bleibt. Jeder Testpunkt schließt eine Gruppe von Aufgaben ab.

| # | Prüft | Aufgaben | Anforderungen | Ergebnis |
|---|---|---|---|---|
| 1 | Dev Container baut und startet; `java -version` meldet 26, `jpegtran -version` antwortet | T1.3, T1.4 | DC-01, DC-02 | *(ausstehend)* |
| 2 | `./gradlew build` läuft durch | T1.6–T1.9 | TE-03 | *(ausstehend)* |
| 3 | `./gradlew test` grün: Protokoll, Client, Fake-Scanner | T1.10–T1.14 | SC-01–SC-07, TE-01 | *(ausstehend)* |
| 4 | `./gradlew test` grün: Befehle gegen den Fake-Scanner | T1.15–T1.17 | BE-01, BE-02, DC-03 | *(ausstehend)* |

Die Aufgaben T1.0 bis T1.2 und T1.5 ändern nur Doku und Konfiguration und brauchen keinen Testlauf.

## Aufgaben

Je Aufgabe: eine ID, genau eine Datei, ein prüfbares Abnahmekriterium und die Anforderungs-IDs, die sie umsetzt. Ein Haken bedeutet: gebaut **und** abgenommen. Geschrieben, aber noch nicht ausgeführt, ist kein Haken.

### Ohne Testlauf

- [x] **T1.0** *(Commit, keine Datei)* – Rahmen als erster Commit. *Abnahme:* `git log --oneline` zeigt `chore: add project framework`; `git ls-files _input` ist leer. *Anforderung:* Ergebnis 1 und 8 (kein ID-Bereich betroffen).
- [x] **T1.1** `docs/plan.md` – Korrekturen aus der Klärungsrunde. *Abnahme:* Versionstabelle mit Java 26; SC-02 definiert „Vorgang"; SC-06 und SC-07 vorhanden; KL-01 nutzt `unboundair.*`/`UNBOUNDAIR_*`; SV-01 nennt beide Testbilder; SV-03 sagt „eine Komponente (Luma)"; CT-01 nur `arm64`; Abschnitt „Entschieden" vorhanden. *Anforderung:* Planpflege nach `AGENTS.md` (kein ID-Bereich betroffen).
- [x] **T1.2** `docs/offene-fragen.md` – offene Punkte mit Status. *Abnahme:* Enthält die 6 Fragen aus dem Wissensstand plus `version`-Antwortformat, 500-ms-Pause und `battlow`, je mit Status und Herkunft; die Abgrenzung zu den offenen Entscheidungen des Plans ist erklärt. *Anforderung:* DO-07.
- [ ] **T1.5** `.gitignore` – Build-Ordner ergänzen. *Abnahme:* `build/` und `.gradle/` ignoriert, `_input/` unverändert enthalten. *Anforderung:* Ergebnis 1 (kein ID-Bereich betroffen).

### Testpunkt 1 – Dev Container

- [ ] **T1.3** `.devcontainer/Dockerfile` – Build- und Testumgebung. *Abnahme:* Basis Temurin JDK 26, installiert `libjpeg-turbo-progs`; keine feste Architektur verdrahtet. *Anforderung:* DC-01.
- [ ] **T1.4** `.devcontainer/devcontainer.json` – Dev-Container-Definition. *Abnahme:* Verweist auf T1.3; im gestarteten Container liefern `java -version` (26) und `jpegtran -version` Ausgaben. *Anforderung:* DC-01, DC-02.

Beide Dateien sind geschrieben, aber **noch nicht verifiziert**: Der Container wurde nie gebaut oder gestartet.

### Testpunkt 2 – Gradle-Gerüst

- [ ] **T1.6** `settings.gradle.kts` – Projektname `unboundair`. *Abnahme:* `./gradlew projects` zeigt den Namen. *Anforderung:* Ergebnis 1 (kein ID-Bereich betroffen).
- [ ] **T1.7** `build.gradle.kts` – Abhängigkeiten und Linter. *Abnahme:* Alle Versionen aus der Tabelle in `plan.md` fest gepinnt, ktlint im Build verdrahtet, `./gradlew build` im Dev Container grün. *Anforderung:* TE-03.
- [ ] **T1.8** `gradle/wrapper/gradle-wrapper.properties` – Wrapper. *Abnahme:* `./gradlew --version` meldet Gradle 9.7.1; die Prüfsumme des mit eingecheckten `gradle-wrapper.jar` stimmt mit der in `entwicklung.md` genannten überein. *Anforderung:* DC-01 (Gradle über den Wrapper).
- [ ] **T1.9** `src/main/kotlin/.../UnboundAirApplication.kt` – Einstiegspunkt. *Abnahme:* Startet und beendet sich ohne Web-Server. *Anforderung:* Ergebnis 1 (kein ID-Bereich betroffen).

### Testpunkt 3 – Scanner-Client

- [ ] **T1.10** `src/main/kotlin/.../scanner/ScannerProtocol.kt` – Befehle und Antworten. *Abnahme:* Alle 4-Byte-Befehle aus dem Wissensstand; Präfix-Vergleich ohne Annahme über Länge oder Padding. *Anforderung:* SC-01, SC-03.
- [ ] **T1.11** `src/main/kotlin/.../scanner/ScannerExceptions.kt` – Fehlerarten. *Abnahme:* Je eine Exception für offline, busy, no paper, battery low, protocol error, timeout. *Anforderung:* SC-05.
- [ ] **T1.12** `src/main/kotlin/.../scanner/ScannerClient.kt` – Scan-Ablauf. *Abnahme:* Status → DPI → Scan → Größe → Daten; Pausen und Timeouts zentral an einer Stelle; Host und Port konfigurierbar; Firmware-Check ≥ 26. *Anforderung:* SC-01, SC-02, SC-04, SC-06, SC-07.
- [ ] **T1.13** `src/test/kotlin/.../scanner/FakeScanner.kt` – Fake-Scanner. *Abnahme:* Füllbytes, geteilte `jpegsize`-Antwort, `devbusy`, Offline und `battlow` lassen sich je einzeln einschalten. *Anforderung:* TE-01.
- [ ] **T1.14** `src/test/kotlin/.../scanner/ScannerClientTest.kt` – Client-Tests. *Abnahme:* Die Abnahmekriterien SC-01 bis SC-07 sind je durch einen Test belegt; die Nutzlast kommt bytegleich an. *Anforderung:* SC-01–SC-07.

### Testpunkt 4 – Befehle

- [ ] **T1.15** `src/main/kotlin/.../cli/StatusCommand.kt` – Befehl `status`. *Abnahme:* Gibt Status und Firmware-Version aus (gegen den Fake: `nopaper` und `NB0a.032`). *Anforderung:* BE-01.
- [ ] **T1.16** `src/main/kotlin/.../cli/ScanCommand.kt` – Befehl `scan`. *Abnahme:* `scan [--dpi 300|600] [--out DATEI]` schreibt das Roh-JPEG; kein Zuschnitt (folgt in Meilenstein 2). *Anforderung:* BE-02 (Teil).
- [ ] **T1.17** `src/test/kotlin/.../cli/CommandTest.kt` – Befehlstests. *Abnahme:* Beide Befehle laufen gegen den Fake-Scanner grün. *Anforderung:* BE-01, BE-02, DC-03.

### Noch offen

- [ ] **T1.18** `README.md` – Einstieg und Wegweiser durch die Doku. *Abnahme:* Erklärt in wenigen Sätzen, was `UnboundAir` ist, nennt den Aufbaustand und verweist auf jede Datei in `docs/` mit einem Satz, wofür sie da ist. Kein Schnellstart – der kommt in Meilenstein 5. *Anforderung:* DO-08.
