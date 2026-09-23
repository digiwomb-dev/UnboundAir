# Entscheidungen

Begründungen zu den festen Entscheidungen. Grundlage ist `docs/plan.md` („Feste Entscheidungen" und „Entschieden – nicht mehr offen"); diese Datei füllt sich nach und nach (DO-06). Stand dieser Session: Test-Dependency-Set, Verzicht auf Testcontainers, Wahl des Mutationswerkzeugs und PDF-Metadaten-Determinismus.

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

## PDF-Metadaten-Determinismus: injizierbare Clock

Golden-Master-Tests brauchen bytegleiche PDFs. PDF-Metadaten (insbesondere `CreationDate`) variieren sonst von Lauf zu Lauf. **Entscheidung: injizierbare Clock** statt fester Konstante.

- `CreationDate` = Scan-Zeitpunkt (Beginn der ersten Seite) aus einer injizierbaren Clock.
- Tests pinnen die Clock auf einen festen Zeitpunkt → bytegleiche PDFs, vollständiger Byte-Vergleich im Golden Master bleibt möglich.
- Die Produktion behält echte Zeitstempel (Dateiname `scan-YYYYMMDD-HHMMSS.pdf` und Metadaten bleiben sinnvoll).

Damit entfällt die Alternative „festes CreationDate" (z. B. Epoche), die zwar einfach und stabil wäre, aber PDFs ohne sinnvolle Zeitangabe erzeugte. Ein rein struktureller Vergleich ohne Voll-Byte-Golden-Master würde die Aussagekraft des Golden Masters schwächen.

## Spike-Ergebnisse (Zusammenfassung)

- **Spike A (kotest-property auf JUnit Platform 6):** läuft. 1 Test, 0 Failures auf Platform 6.0.3 (Spring Boot 4.1.1, `junit-jupiter` 6.0.3).
- **Spike B (Mutation):** PIT funktioniert (Zahlen oben), kein Fallback nötig.
