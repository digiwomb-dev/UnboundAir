# Entscheidungen

Begründungen zu den festen Entscheidungen. Grundlage ist `docs/internal/plan.md` („Feste Entscheidungen" und „Entschieden – nicht mehr offen"); diese Datei füllt sich nach und nach (DO-06).

## Stack und Versionspolitik: neueste stabile, gepinnt — nicht LTS

**Entscheidung: Kotlin + Spring Boot, Build mit Gradle (Kotlin DSL) inklusive Wrapper, jeweils die neueste stabile Version, im Build fest gepinnt — ausdrücklich nicht nur LTS.**

Kotlin, weil der Dienst klein, nebenläufig und stark typgetrieben ist (Scanner-Protokoll, Verarbeitungsschritte, Outbox-Zustände): Nullsicherheit und Datenklassen tragen hier mehr als jede Framework-Wahl. Spring Boot, weil Konfiguration, Lebenszyklus und HTTP-Client damit Bordmittel sind, statt selbst gebaut zu werden. Gradle mit Kotlin DSL, weil Build-Logik damit typgeprüfter Code ist statt einer zweiten Skriptsprache; der Wrapper gehört dazu, damit jede Umgebung denselben Build fährt.

**Warum nicht LTS.** Ein LTS-Grundsatz würde den Stack auf der jeweils ältesten noch gepflegten Version einfrieren und jede Neuerung um Jahre verzögern — bei einem Projekt, das keine Altsystem-Rücksichten hat und dessen einzig riskante Logik ohnehin in reinen Kotlin-Klassen ohne Framework liegt. „Neueste stabile, gepinnt" kauft dagegen aktuelle Compiler, aktuelle Bibliotheken und reproduzierbare Builds zugleich: gepinnt, damit ein Build morgen dasselbe tut wie heute; neueste stabile, damit ein Update ein kleiner Schritt bleibt statt eines Sprungs über drei Jahre.

Die verworfene Alternative wäre **LTS als Obergrenze** (Java 25 statt 26, ältere Boot- und Kotlin-Linien). Ihr Preis wäre doppelt: Der Code dürfte neuere Sprach- und Bibliotheksmittel nicht nutzen, und wenn das LTS ausläuft, steht ein großer Sprung an — genau die Sorte Migrationsprojekt, die dieser Grundsatz vermeiden soll.

**Die Kette begrenzt sich selbst.** Die Obergrenze setzt jeweils der älteste Baustein: Gradle begrenzt Java, Spring Boot begrenzt Kotlin. Beim Anheben einer Version ist deshalb die Tabelle in `docs/internal/plan.md` mitzupflegen — wer Java anhebt, prüft Gradle; wer Kotlin anhebt, prüft Spring Boot. Das ist kein Zufall, sondern die Kehrseite von „neueste stabile": Ohne diese Regel würde ein Update das nächste stillschweigend sprengen.

## Warum nicht Java 27

**Entscheidung: Java 26, nicht 27 — obwohl 27 seit dem 15.09.2026 verfügbar ist.**

Gradle 9.7.1 gibt in seiner Kompatibilitätsmatrix ausdrücklich an, JVM 27 und neuer nicht auszuführen (Spanne 17–26). Wer Java 27 nähme, könnte den Build schlicht nicht starten — kein schleichender Fehler, sondern ein harter. Die Alternative **„Java 27 trotzdem"** kostet also den Build selbst und scheidet aus, solange Gradle nicht nachzieht. Sobald Gradle nachzieht, ist Java 27 der nächste Schritt: Der Grundsatz bleibt „neueste stabile Version", nur die Decke liegt derzeit bei 26.

## Kotlin über der verwalteten Version

**Entscheidung: Kotlin 2.4.20 — bewusst neuer als die von Spring Boot 4.1.1 verwaltete Version 2.3.21.** Das ist die einzige Stelle, an der bewusst von Spring Boots verwalteten Versionen abgewichen wird.

Der Grund ist ein Bytecode-Ziel, kein Geschmack: Der Compiler von Kotlin 2.3.21 kennt als höchstes Bytecode-Ziel `JVM_25` — mit Java 26 lässt sich damit nicht bauen. Kotlin 2.4.20 kennt `JVM_26`. Wer die verwaltete Version behielte, müsste Java auf 25 zurückdrehen und gäbe damit den Versionsgrundsatz an der ersten Kante auf. Die Überschreibung im Build kauft also Java 26 zum Preis einer einzigen, benannten Abweichung von der Boot-Verwaltung.

Der benannte **Rückfallweg ist Java 25 statt 26**: Falls die Überschreibung je Probleme macht (etwa eine Inkompatibilität zwischen Boot-Verwaltung und neuerem Compiler), wird nicht an der Kotlin-Version gefeilt, sondern Java auf 25 zurückgenommen — dann passt die verwaltete Version wieder, und der Build ist ohne Sonderweg grün. Das ist bewusst ein Rückschritt auf eine definierte Stufe, kein Suchen nach einer dritten Version.

## Lizenz: Apache-2.0

**Entscheidung: Apache-2.0.**

Wie MIT freizügig — verwenden, verändern, weitergeben, auch kommerziell —, aber mit ausdrücklicher Patentklausel. Das ist hier kein Formkram: Der Dienst baut ein Hersteller-Protokoll nach, und genau für diese Lage ist die Patentklausel da — sie gibt jedem Nutzer das Patentrecht, das zum Betrieb des Codes nötig ist, statt darüber zu schweigen wie MIT.

**Herkunft wird dokumentiert, nicht vermischt.** s400w steht unter CC0 — übernommen wurde kein Code, nur Protokollwissen —, AirScan ist als Quelle genannt, Hersteller-Code liegt nicht im Repo. Die verworfene Alternative wäre **MIT** (einfacher, aber ohne Patentklausel — eine Lücke genau an der Stelle, wo dieses Projekt sie am ehesten spürte) oder **eine Copyleft-Lizenz** (würde jeden Einbetten-und-Weitergeben-Fall mit Lizenzpflichten belegen und damit schlicht weniger Nutzer erreichen, ohne dem Projekt etwas zu geben). Die `LICENSE` liegt im Wurzelverzeichnis des Repos; die Begründung steht hier.

## Linter: ktlint über Spotless

**Entscheidung: ktlint als Regelwerk (1.8.0), ausgeführt über das Spotless-Gradle-Plugin (8.10.2).**

ktlint prüft reine Formatierung, braucht kaum Konfiguration und rauscht wenig. Die verworfene Alternative **detekt** würde mehr Feinjustierung verlangen — eigene Regelsätze, eigene Schwellen —, ohne hier mehr zu bringen: Es gibt keine komplexen Architektur- oder Komplexitätsregeln zu erzwingen, nur einheitliche Formatierung. detekt wäre mehr Werkzeug für ein Problem, das nicht vorliegt.

**Der Umweg über Spotless ist nötig, nicht Geschmack.** Das ktlint-Gradle-Plugin scheiterte mit `Extensions storage is not registered`. Ursache ist eine Kette aus drei Gliedern: `spring-boot-dependencies` importiert das Kotlin-BOM, dieses verwaltet auch `kotlin-compiler-embeddable`, und `io.spring.dependency-management` wendet das auf *alle* Konfigurationen an — also auch auf die des Linters. Dadurch bekommt ktlint statt seines eigenen Compilers den des Projekts untergeschoben und stürzt ab. Spotless löst seine Werkzeuge über eine `detachedConfiguration` auf, die von `configurations.all {}` nicht erfasst wird — die Überschreibung greift dort schlicht nicht.

**Erschwerend hinkt der Linter der Sprache hinterher.** ktlint ist mit Kotlin 2.4 grundsätzlich nicht kompatibel (ktlint-Issue 3289, gemeldet von einem JetBrains-Compiler-Entwickler; der Fix steckt bisher nur in ktlint 2.0.0-ALPHA). Der Linter parst deshalb bewusst mit einem älteren Compiler (2.2.21) als dem, mit dem übersetzt wird (2.4.20) — für Formatierungsregeln genügt das. Was das kostet, steht in OF-11: Syntax, die erst nach Kotlin 2.2 hinzukam, versteht der Linter nicht; äußert sich das je, dann als Parse-Fehler in einer einzelnen Datei, nicht als falsches Urteil über den Code.

## Der Kern bleibt frei von Spring — mit einer benannten Ausnahme

**Entscheidung: In keinem Kern-Paket darf `org.springframework..` auftauchen — mit genau einer benannten Ausnahme: `output.paperless` darf den Spring-eigenen HTTP-Client verwenden (`RestClient` samt `spring-web`-Typen für Multipart und Header).**

Der Grund ist Testbarkeit und Lebensdauer: Der Kern (Scanner, Verarbeitung, Batch, Ausgabe-Logik) ist ohne Spring-Kontext konstruierbar und damit als reine Unit-Tests prüfbar; er überlebt Framework-Updates, weil er das Framework nicht kennt. Einstellungen kommen als Konstruktor-Werte aus der Kompositionswurzel (Muster `PageSettings`), nicht als injizierte Properties — dasselbe Muster, das die Schichten-Tabelle für `config` festschreibt.

**Warum die Ausnahme existiert.** Das Hochladen ist der einzige Punkt in v1, an dem ein Kern-Paket nach außen spricht. Der Spring-Client ist ohnehin da, weil Spring Boot ihn mitbringt — die Ausnahme nutzt genau diesen Client, statt einen zweiten einzuziehen. Die Ausnahme kauft also genau einen Client statt zwei.

**Was selbst dort verboten bleibt:** keine Spring-Stereotypen (`@Component` und Verwandte), kein injiziertes `UnboundAirProperties`. `output.paperless` bekommt seine Werte wie überall im Kern als Konstruktor-Werte aus der Kompositionswurzel. Die verworfene Alternative wäre **Spring überall im Kern** (bequem: injizieren statt durchreichen) — ihr Preis wäre ein Kern, der nur noch im Spring-Kontext testbar ist und bei jedem Framework-Sprung mitwandern muss. Oder umgekehrt **gar kein Spring-Typ im Kern** (reiner als rein) — ihr Preis wäre ein zweiter HTTP-Client nur für einen einzigen Upload-Pfad.

## Laufzeit: normale JVM

**Entscheidung: normale JVM. GraalVM Native Image ist eine spätere Option, nicht v1 — aber nichts einbauen, was sie verbaut.**

Ein Native Image brächte schnelleren Start und kleineren Speicherfuß — für einen Dienst, der als Container dauerhaft läuft und auf Scans wartet, ist beides in v1 kein Engpass. Was zählt, ist, die Option offenzuhalten: keine Abhängigkeiten mit nativen Bibliotheken ohne Not (siehe den brotli4j-Ausschluss), keine Konfigurationsmagie, die nur auf der HotSpot-JVM funktioniert. Die verworfene Alternative wäre **Native Image sofort**: Sie würde Meilensteine an Reflexions-Konfiguration, ImageIO/AWT-Prüfung und OpenPDF-Verhalten im Native Image binden — Arbeit, die erst lohnt, wenn der Dienst steht und seine Engpässe bekannt sind.

Diese Entscheidung ist der Grund hinter der Laufzeit-Modulauswahl (kein `@ConditionalOnProperty`, siehe AU-03-Entscheidung): Was zur Laufzeit gewählt wird, statt zur Build-Zeit verdrahtet zu sein, überlebt den späteren Wechsel des Laufzeitmodells.

## Eine Anwendung, keine Web-UI in v1

**Entscheidung: eine Anwendung, in v1 ohne Web-Oberfläche. Den Kern so schneiden, dass später eine Web-UI andocken kann, ohne den Kern umzubauen.**

Der Dienst hat genau einen Auftrag — einlegen und fertig — und eine Web-UI würde in v1 bedeuten: HTTP-Schicht, Authentifizierung, Oberflächen-Tests und -Pflege für einen Nutzen, den noch niemand eingefordert hat. Die verworfene Alternative wäre **die UI gleich mitzubauen**: Ihr Preis wäre ein verdoppelter Oberflächen- und Testaufwand für ein Produkt, dessen Kern (Scannen, Zuschneiden, PDF, Module) im Fokus steht.

„Keine UI" heißt dabei nicht „kein Platz für eine UI": Der Schnitt (Kern-Pakete als reine Logik, `service` als Orchestrierung, Einstellungen als Konstruktor-Werte) ist so gelegt, dass eine UI später andockt, statt den Kern aufzubrechen. Das ist dieselbe Schnitt-Logik wie bei der Batch-Senke: Wer später dazukommt, hängt sich an, statt umzubauen.

## Container-Abnahme auf dem GitHub-Actions-`arm64`-Runner (CT-01)

**Entscheidung: In v1 nur `linux/arm64`; die Abnahme läuft auf einem GitHub-Actions-`arm64`-Runner — nativer Hardware statt Behauptung.**

Der Ziel-Host ist ARM-Hardware, und ein Image, das nur per Cross-Build für eine andere Architektur entsteht, ist ungetestet: Es baut vielleicht, aber ob `jpegtran`, `jbig2` und der Dienst darin wirklich laufen, weiß niemand. Die verworfene Alternative wäre **beide Architekturen sofort** (`arm64` + `amd64`) oder **Abnahme per Emulation** (etwa QEMU): Beides verdoppelt Prüfaufwand und Fehlersuche an einer Stelle, wo v1 nur eine Plattform braucht — und Emulation beweist gerade das nicht, worauf es ankommt (dass das Image auf echter Hardware läuft). Deshalb: ein Image, eine Architektur, echte Hardware.

Das Dockerfile wird dabei so geschrieben, **dass es keine Architektur fest verdrahtet** — `linux/amd64` kommt später dazu, ohne das Dockerfile umzuschreiben. Die Abnahme ist dreiteilig und steht in CT-01: Im Container laufen `jpegtran -version`, `jbig2 -V` (schreibt nach stderr, Exit 0 — eine naive Prüfung von stdout findet nichts) und `status` gegen den Fake-Scanner.

## Git-Ablauf: `dev` als Integrations-Branch, `main` nur per PR (05.10.2026)

**Entscheidung: `main` ist geschützt und nimmt Änderungen ausschließlich per Pull Request an; `dev` ist der Integrations-Branch und bleibt bewusst ungeschützt.**

Bis Meilenstein 4 ging jeder Meilenstein-Branch per PR direkt nach `main`. Das trug, solange ein Meilenstein ein geschlossenes Paket war. Es trägt nicht mehr, sobald kleine Änderungen dazwischenkommen, die zu keinem Meilenstein gehören — der Workflow für den Mutationslauf unten ist genau so ein Fall. Ohne Zwischenstufe landen die entweder direkt auf `main` (dann ist der Schutz eine Absichtserklärung) oder sie warten auf den nächsten Meilenstein (dann blockiert Infrastruktur die Arbeit, die sie stützen soll).

Der Schutz gilt **auch für Administratoren** (`enforce_admins`), und das ist der Punkt: Eine Regel, von der sich der Inhaber des Repositorys ausnehmen kann, ist bei einer Ein-Personen-Arbeit samt Agent keine Regel. Pflicht-Reviews sind dagegen auf **null** gesetzt — bei einem Arbeitsmodus, in dem derselbe Mensch abnimmt, wäre eine erzwungene Selbst-Freigabe ein Klick ohne Erkenntnis. Der PR erzwingt die Zusammenfassung und den Diff an einer Stelle; das ist der Gewinn, nicht das Häkchen.

`dev` bleibt ungeschützt, weil der Agent dort nach jedem abgenommenen Schritt committet. Ein PR je Schritt stünde gegen die kleinen Schritte aus `AGENTS.md`.

## Der Mutationslauf läuft auf Abruf in CI, im Dev-Container-Image (TE-04)

**Entscheidung: Der volle PIT-Lauf ist zusätzlich als GitHub-Actions-Workflow verfügbar (`workflow_dispatch`, nur von Hand) und führt `./gradlew pitest` im Dev-Container-Image aus.**

Der Lauf dauert rund 2,5 Stunden und belegt dabei Gradle-Daemon, Kotlin-Daemon und die PIT-Prozesse gleichzeitig im RAM — auf einem kleinen Host stirbt der Gradle-Daemon dabei („daemon disappeared"). Eine Messung, die den Entwicklungsrechner einen halben Tag lahmlegt, wird seltener gemacht als nötig; TE-04 verlangt sie aber nach jedem Meilenstein, der ein Kern-Paket hinzufügt. Also gehört sie dorthin, wo Wartezeit nichts kostet.

**Kein `push`-Trigger.** Der Workflow startet ausschließlich manuell. 2,5 Stunden bei jedem Commit wären nach einer Woche abgeschaltet, und der Lauf ist bewusst kein Gate: Er ist die Einmessung der Messgrundlage, nicht die Ampel über jedem Push. `concurrency` mit `cancel-in-progress` ersetzt einen laufenden Start durch den neuen, statt parallel weitere 2,5 Stunden zu verbrennen.

**Im Dev-Container-Image, nicht in einer Runner-Nachbildung.** Das ist die eigentliche Entscheidung. Die Zahlen aus `docs/internal/entscheidungen.md` stammen bisher alle aus dem Dev Container; eine CI-Umgebung, die JDK, `jpegtran` und `jbig2` eigenständig installiert, wäre eine zweite Wahrheit über die Entwicklungsumgebung und ihre Zahlen nicht mit den bisherigen vergleichbar. Deshalb baut der Workflow `.devcontainer/Dockerfile` und läuft darin — dieselbe Datei, die lokal gilt. Runner ist `ubuntu-24.04-arm`, native `arm64`-Hardware wie bei der Imageprüfung, weil die Golden-Dateien gegen die Binärprogramme dieser Architektur aufgenommen sind.

**Ohne die `devcontainers/ci`-Action.** Sie würde die folgenden Schritte (Cache, Artefakt-Upload) ebenfalls im Container ausführen; das sind JavaScript-Actions, das Image bräuchte also Node allein für CI. Ein schlichtes `docker run` liefert dieselbe Umgebung, ohne den Dev Container für CI-Zwecke zu verändern.

**Der Bericht wird hochgeladen** (`build/reports/pitest/`, 90 Tage — das Maximum bei GitHub). Er liegt nur auf dem Runner; ohne diesen Schritt bliebe von 2,5 Stunden nichts als grün oder rot, und die Zahlen je Paket, die TE-04 verlangt, wären verloren.

**Verhältnis zu „CI erst mit Meilenstein 6":** Vorgezogen sind damit genau zwei Bausteine — diese Einmessung und die CT-01-Imageprüfung. Beide prüfen etwas, das lokal nicht oder nur teuer prüfbar ist. Tests, Release und Veröffentlichung bleiben Meilenstein 6.

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

**kotest-property statt jqwik.** jqwik führt ab Version 1.10 eine Nutzungsbeschränkung in seinen Bedingungen ein („Anti-AI Usage Clause"), die dieses Projekt nicht übernimmt. Dazu kommt ein handfesteres Argument: Dieselbe Version schreibt absichtlich Text in die Standardausgabe, der nicht zum Testergebnis gehört — die Release Notes des Projekts nennen das selbst und erklären Release 1.10.0 für nicht mehr verwendbar. Eine Test-Abhängigkeit, die bewusst Nutzlast in ihre Ausgabe legt, ist ein Lieferketten-Risiko, unabhängig davon, wer den Code schreibt. kotest-property ist Apache-2.0, ohne solche Klausel, Kotlin-nativ und registriert keine eigene JUnit-Engine, läuft also konfliktfrei neben JUnit Jupiter. Spike A bestätigt: `forAll` in einer `@Test`-Methode läuft auf JUnit Platform 6.0.3 grün (ein Test, 0 Failures). Hinweis für die Praxis: `forAll`/`checkAll` geben einen Rückgabewert zurück, die `@Test`-Methode braucht daher einen Block-Body.

**WireMock 3.13.2 statt 4.x.** Die 4.x-Linie ist Stand September 2026 weiterhin Beta (4.0.0-beta.37); „neueste stabile" ist daher 3.13.2.

**ArchUnit 1.5.0 mit `archunit-junit6`.** Seit ArchUnit 1.5.0 gibt es das Artefakt `archunit-junit6` mit JUnit-Platform-6-Unterstützung. Engine-ID ist `archunit` (verifiziert), daher `includeEngines("junit-jupiter", "archunit")`.

**Awaitility und AssertJ bleiben verwaltet.** Beide bringt `spring-boot-starter-test` in gepinnten, von Spring Boot gepflegten Versionen mit (Awaitility 4.3.0, AssertJ 3.27.7). Eine eigene Pin-Stelle würde nur Duplikation erzeugen, ohne etwas zu gewinnen.

**JSON-Schema-Validator erst mit der Contract-Schicht.** Der Validator (networknt 3.0.7) wird gebraucht, sobald die erste Contract-Testdatei Antworten gegen ein Schema prüft. Bis dahin bleibt die Dependency ungenutzt und kommt erst mit Meilenstein 4 dazu.

## Verzicht auf Testcontainers

Testcontainers ist keine Option. Drei Gründe, die zusammenspielen:

1. **DC-03:** `./gradlew test` muss im Dev Container komplett offline grün laufen, ohne echte Geräte oder Dienste. Testcontainers würde eine Container-Laufzeit im Test voraussetzen und echte Dienste (paperless-ngx, ggf. später eine Datenbank) hochziehen.
2. **Keine externen Dienste in v1:** Der Dienst hat in v1 bewusst keine Datenbank und kein Web; es gibt schlicht keinen Dienst, der einen Container rechtfertigt. paperless wird mit WireMock, der Scanner mit dem Fake-Scanner ersetzt — beides deckt die Verträge präziser ab als eine echte Instanz.
3. **GraalVM Native Image:** Nichts einbauen, was die spätere Native-Image-Option verbaut.

Entscheidung des Auftraggebers (siehe `docs/internal/plan.md`, DC-03).

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

**Nachgerechnet: Es ist nicht nur ein Wechsel der Messgrundlage.** TE-04 erlaubt die Neueinmessung, *wenn das Ziel wächst* — wenn also Pakete hinzukommen und sich der Nenner ändert. Rechnet man den Lauf allein über die fünf Pakete, die schon zu Meilenstein 3 gemessen wurden, ergibt sich **67,9 % (389/573)** gegenüber damals **70,7 % (371/525)**. Auf gleicher Grundlage ist der Score also um knapp drei Punkte gefallen. Die Ursache ist `service`: Das Paket war bereits Teil der Messung und ist von 59 % auf 51 % gefallen, weil es mit `OutputPipeline` und `OutboxRunner` zwei untergetestete Klassen aufgenommen hat (4 → 6 Klassen, Nenner 100 → 137).

Für diesen Teil des Rückgangs gibt TE-04 keine Deckung. Er steht hier, weil er sonst im größeren Nennerwechsel verschwinden würde: Von den fünf Punkten zwischen 71 % und 66 % gehen rund drei auf neu hinzugekommenen, zu wenig geprüften Code in einem bereits gemessenen Paket zurück und nur die übrigen auf die beiden neuen Pakete. Das ist kein Argument gegen die Schwelle 66 — die bildet den gemessenen Stand ab —, wohl aber eines dafür, dass Issue [#138](https://github.com/digiwomb-dev/UnboundAir/issues/138) `service` zuerst angeht.

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

**Entscheidung: zwei neue Schichten.** `config` als Blatt ohne eigene Abhängigkeiten, `service` als Orchestrierung darüber. Die vollständige Richtungstabelle steht in `docs/internal/plan.md`.

Drei Punkte, die dabei bewusst so und nicht anders entschieden sind:

- **`scanner`, `image` und `processing` bleiben frei von `config`.** Naheliegend wäre, die neuen `@ConfigurationProperties` überall direkt zu injizieren. Das würde den Kern aber an Spring binden: Die Klassen sind heute ohne Kontext konstruierbar und damit als reine Unit-Tests prüfbar. Sie bekommen ihre Werte weiter über Konstruktor-Parameter mit Defaults (Muster `PageSettings`); das Umsetzen von Properties auf diese Parameter ist Aufgabe der Kompositionswurzel. Dass die drei Pakete Blätter sind, ist damit eine vom Wächter geprüfte Regel und keine Absichtserklärung.
- **`cli` darf `service` sehen, aber weiterhin nicht `output`.** Der Befehl `run` startet den Dienst, deshalb braucht die CLI Zugriff auf `service`. Der Weg zur Ausgabe führt aber weiter ausschließlich über `service` – die CLI soll kein PDF bauen und kein Modul ansprechen.
- **Der Wächter bekommt erstmals auch eingehende Regeln.** Bisher prüfte er nur, worauf eine Schicht zugreifen darf. Eine Schicht, die niemand deklariert, wäre damit völlig ungeprüft geblieben. Mit `service` als oberster Nutzschicht kommt die Gegenrichtung dazu: Auf `service` darf nur `cli` zugreifen, nicht der Kern.

## Dokument-Typ der Modul-Schnittstelle: eigenes `output.OutputDocument` (AU-02)

**Entscheidung: Die Modul-Schnittstelle bekommt einen eigenen Typ `output.OutputDocument`; die Batch-Senke bildet `service.ScannedDocument` darauf ab — nicht umgekehrt.**

Der Grund ist die Richtungstabelle oben: `output` darf `service` nicht sehen. Bekäme die Schnittstelle `ScannedDocument` direkt, müsste entweder `output` auf `service` zugreifen (Regelbruch, vom Wächter verboten) oder `ScannedDocument` nach `output` wandern — dann hinge aber die CLI, die Batches baut und übergibt, an der Ausgabe-Schicht. Die paar Zeilen Abbildung in der Senke sind der günstigere Preis: Sie halten beide Richtungen sauber — `service` kennt `output`, nie umgekehrt — und belegen zugleich AU-02 an einer echten Stelle (siehe „Batch-Übergabe"): Ein neues Modul hängt sich an `OutputDocument`, ohne den Kern zu ändern.

Die verworfenen Alternativen wären **`ScannedDocument` direkt als Dokument-Typ** (spart die Abbildung, kostet aber einen Schichtbruch oder eine Kopplung der CLI an `output` — beides teurer als ein Mapping) oder **`ScannedDocument` nach `output` verschieben** (hält den Wächter grün, zieht aber die CLI in die Abhängigkeit der Ausgabe-Schicht hinein: Wer `run` startet, müsste `output` kennen, obwohl der Weg dorthin ausschließlich über `service` führen soll).

## Logging: SLF4J mit Logback, Senken bleiben Lambdas

Bis Meilenstein 2 gab es kein Logging-Framework – Ausgaben liefen über `println`/`System.err.println` in der Kompositionswurzel, Meldungen aus dem Kern über `warn: (String) -> Unit`-Lambdas. Für KL-02 (Zeile je Seite mit vier Messwerten) und DL-02 („nur beim Zustandswechsel loggen") reicht das nicht: Es fehlen Level, Zeitstempel und ein sauberer Zugriff im Test.

**Entscheidung: SLF4J als Fassade, Logback als Implementierung.** Beide bringt `spring-boot-starter` bereits mit. Logback schreibt per Default auf stdout, was KL-02 ohnehin verlangt (journald-freundlich). Im Test hängt sich ein `ListAppender` an den Logger, statt stdout abzufangen.

**Was ausdrücklich bleibt:** Kommandos, Verarbeitungsschritte und der Scanner-Client loggen **nicht** selbst. Sie melden weiter über ihre `warn`-Senke nach oben; nur `service` und die Kompositionswurzel schreiben Log-Zeilen. Das ist kein Schönheitsprinzip: Der Kern bleibt dadurch ohne Logger-Attrappe testbar, und ein Aufrufer entscheidet, ob eine Meldung ein Log-Eintrag, eine CLI-Zeile oder später eine Web-UI-Benachrichtigung wird.

Verworfen wurde `kotlin-logging`. Es ist bequemer, aber eine zusätzliche Abhängigkeit für syntaktischen Zucker über derselben Fassade.

## Batch-Übergabe: eine Senke statt einer vorgezogenen Outbox

DL-04 verlangt, dass ein geschlossener Batch „an die Outbox" geht. Die Outbox ist aber AU-04 und gehört zu Meilenstein 4 – in Meilenstein 3 gibt es sie noch nicht.

**Entscheidung: Der Batch übergibt an eine Senke vom Typ `(ScannedDocument) -> Unit`.** In Meilenstein 3 schreibt diese Senke das PDF in ein Verzeichnis. In Meilenstein 4 wird die Outbox eingehängt – **ohne eine Zeile am Batch zu ändern**.

Die beiden Alternativen waren schlechter:

- **Die Outbox in Meilenstein 3 vorziehen** hieße, AU-04 (Persistenz, Retry mit Backoff, Neustart-Festigkeit) zu bauen, bevor es überhaupt ein Modul gibt, an das zugestellt werden könnte. Das verschiebt Arbeit, ohne sie zu verkleinern, und macht den Meilenstein unscharf.
- **Eine eigene Schnittstelle mit einer Wegwerf-Implementierung** wäre mehr Zeremonie für dasselbe Ergebnis. Das Projekt verwendet das Lambda-Muster bereits an derselben Stelle im Code (`warn: (String) -> Unit`); eine zweite Konvention für denselben Zweck wäre unnötig.

Nebeneffekt: Damit ist AU-02 („neue Module lassen sich ergänzen, ohne den Kern zu ändern") an einer echten Stelle belegt, statt nur behauptet zu werden.

## Mehrseitige Dokumente über ein Zeitfenster; Drehen und Geraderücken später

**Entscheidung: Mehrseitige Dokumente entstehen über ein Zeitfenster (`batch-timeout`, vorläufiger Default 20 s, endgültig nach der Messung zu OF-03); Drehen und Geraderücken kommen später in den Dienst, nicht in v1 — im paperless-Pfad übernimmt das paperless selbst (OCRmyPDF).**

Der Grund ist Unwissen, das ehrlich gebaut wird: Wie lange jemand zum Nachlegen braucht, ist nicht gemessen (OF-03), also wird das Fenster konfigurierbar gebaut statt geraten-fest verdrahtet. Die verworfene Alternative wäre **eine feste, „vernünftig" wirkende Grenze** (etwa: jedes Blatt ein Dokument, oder ein hart verdrahtetes Fenster) — ihr Preis wäre ein Dienst, der Dokumente zerreißt oder Nutzer warten lässt, ohne dass jemand sagen könnte, warum genau diese Grenze gilt.

**Warum Rotation wartet — und warum Geraderücken um kleine Winkel auf die guard rail trifft.** Drehen um 90/180/270° ginge mit `jpegtran -rotate` verlustfrei und bleibt deshalb als späterer Verarbeitungsschritt offen (die Kette nach SV-07 nimmt ihn ohne Umbau auf). Geraderücken um kleine Winkel geht dagegen nur mit Neukomprimierung — das widerspricht „Nie neu komprimieren" (Abschnitt unten) und muss vorher entschieden werden (siehe „Offene Entscheidungen" im Plan). Dass paperless im v1-Pfad via OCRmyPDF geraderückt, kauft Zeit: In v1 muss der Dienst das Problem nicht lösen, das er sich mit der guard rail selbst verbietet.

## Scanner-Antworten per Präfix vergleichen (SC-03)

**Entscheidung: Antworten werden per Präfix verglichen — ohne eine bestimmte Länge oder ein bestimmtes Padding vorauszusetzen.**

Das Gerät hängt Füllbytes an: Status- und Bestätigungsantworten sind 11 Byte (Wort + `\x00`-Padding + `H`, etwa `nopaper\x00\x00\x00H`). Wer auf exakte Gleichheit mit einer 11-Byte-Erwartung prüfte, wäre an diese Form gekettet. Die `version`-Antwort folgt dem Schema aber nicht — der Referenz-Fake sendet `NB0a.032\x00`, also 9 Byte ohne abschließendes `H`, und ob das echte Gerät es genauso macht, ist offen (OF-07). Der Vergleich darf deshalb weder die Länge noch das Padding annehmen: Er prüft, ob die Antwort mit dem erwarteten Wort beginnt, und sonst nichts.

Die verworfene Alternative wäre **exakter Byte-Vergleich** (einfach zu schreiben, einfach zu lesen). Ihr Preis wäre Sprödigkeit genau an der Stelle, wo das Protokoll am unsichersten ist: Passt eine einzige Antwort nicht ins 11-Byte-Schema — wie `version` heute schon —, bricht der Client an einer funktionierenden Antwort. Der Präfix-Vergleich kostet eine Zeile mehr und kauft dafür einen Client, der in beiden OF-07-Fällen funktioniert.

## `scan` liefert die effektive Auflösung mit (SC-08)

`ScannerClient.scan(dpi)` gab bisher nur die JPEG-Bytes zurück. Fällt der Firmware-Check nach SC-07 aus – Gerät kann kein 600 dpi –, stuft der Client still auf 300 zurück und meldet das nur als Warnung an die `warn`-Senke. Der Aufrufer bekommt die tatsächlich verwendete Auflösung nicht.

Bis Meilenstein 2 war das folgenlos: Die DPI landete nur im Dateinamen. Mit SV-05 (Seitengröße im PDF = Pixel ÷ DPI) wird sie zu einer **maßgeblichen Größe**. Ein Scan, der mit 600 angefordert und mit 300 geliefert wird, ergäbe eine PDF-Seite in halber Kantenlänge – ein Fehler, den niemand im Log sucht, weil das Dokument ansonsten unauffällig aussieht.

**Entscheidung: `scan` liefert Bytes und effektive Auflösung gemeinsam zurück.** Damit ist die Zahl, die die Seitengröße bestimmt, dieselbe, die das Gerät tatsächlich benutzt hat. Die Warnung bleibt zusätzlich bestehen – sie erklärt dem Menschen, warum die Auflösung abweicht.

Das ist eine Änderung an bestehendem Code aus Meilenstein 1 und geschieht deshalb früh in Meilenstein 3, bevor PDF-Erzeugung und Dienst-Loop darauf aufbauen.

## DPI-Quelle: befohlene Auflösung gilt, Header warnt (SV-05)

**Entscheidung: Maßgeblich ist die befohlene Auflösung (`dpi300`/`dpi600` setzen wir selbst); der JPEG-Header wird zusätzlich gelesen, und eine Abweichung warnt statt abzubrechen.**

Der Grund steht in OF-05: Die physische Größe stimmt ohnehin nicht (A4 misst 206,9 × 291,3 mm statt 210 × 297 mm, das DL-Kuvert 103,0 × 211,2 mm statt 110 × 220 mm) — unklar ist, ob der Scanner beschneidet, der Einzug staucht oder die Header-Angabe schlicht nicht der optischen Auflösung entspricht. Der Header ist also nicht vertrauenswürdiger als unser eigener Befehl. Ihn zur zweiten Autorität zu machen hieße, einer unsicheren Quelle Vetorecht zu geben. Zugleich ist eine Abweichung ein wertvoller Hinweis (falscher Modus, unerwartetes Gerät), deshalb wird sie geloggt statt verschwiegen — siehe dazu den Nachbarabschnitt zu SC-08: Die Zahl, die die Seitengröße (Pixel ÷ DPI) bestimmt, ist die effektive aus dem Scan-Rückgabewert, nicht eine still angenommene.

Die verworfenen Alternativen wären **Header maßgeblich** (der Scan „weiß, was er ist" — kostet aber Abbrüche oder Maßfehler, sobald der Header lügt, was er nach OF-05 gerade tut) oder **Header ignorieren** (spart das Lesen, kostet aber den einzigen Hinweis, dass Befehl und Wirklichkeit auseinanderlaufen).

## Nie neu komprimieren — mit zwei benannten Ausnahmen

**Entscheidung: Der Scanner liefert JPEG mit Qualität ~50; Zuschnitt und Graustufen laufen ausschließlich über `jpegtran` (`-crop`, `-grayscale`), JPEGs werden per `Image.getInstance` unverändert als `/DCTDecode` ins PDF eingebettet, die Seitengröße kommt aus Pixeln und DPI. Zwei benannte Ausnahmen: optionales `normalize` (Default aus, SV-04) und `bw` (1-bit, SV-08).**

Das ist die zentrale Leitplanke des Projekts: Jede Neukomprimierung würde aus einem Qualität-50-JPEG ein schlechteres machen — irreversibel, pro Seite, ohne dass ein Betrachter je sagen könnte, woher die Artefakte kommen. `jpegtran` arbeitet dagegen auf DCT-Koeffizienten: Schneiden und Entgrauen ohne einen einzigen Dekodier-Kodier-Zyklus. Dass das Roh-Einbetten wirklich roh ist, ist gemessen, nicht behauptet (Spike #141: `/DCTDecode`, Rohstrom bytegleich; der Code-Kommentar in `PdfBuilder` warnt vor jedem Weg über `java.awt.Image`/`BufferedImage`, der neu kodierte).

**Warum die Ausnahmen keine Aufweichung sind.** `normalize` ist der einzige Pfad mit Neukomprimierung — ausdrücklich optional, Default aus, in der Doku als verlustbehaftet markiert — und solange Zweck und Werkzeug offen sind, wird er gar nicht gebaut (OF-09 ist in den Plan verschoben). `bw` verlässt den `jpegtran`-Pfad zwangsläufig: Eine 1-bit-Umwandlung kann keine DCT-Koeffizienten-Transformation sein, also läuft sie über Schwellwert (Default 128) nach PBM und von dort als JBIG2 mit gemeinsamem Wörterbuch ins PDF (Abschnitt zu `jbig2` unten). Benannt und begrenzt heißt: Die Regel nennt ihre Ausnahmen beim Namen, statt zu schweigen, wo sie endet — eine dritte Ausnahme gibt es nicht, ohne dass sie hier stehen müsste.

Die verworfene Alternative wäre **Verarbeitung mit einer Allzweck-Bibliothek** (bequem: schneiden, skalieren, normalisieren aus einer Hand). Ihr Preis wäre eine stille Neukomprimierung jeder Seite — genau der Qualitätsverlust, den diese Entscheidung verbietet — plus eine große zusätzliche Systemabhängigkeit.

## JBIG2-Kodierung über das externe Programm jbig2 (Spike #140)

**Das gemeinsame Symbolwörterbuch existiert.** Der Aufruf `jbig2 -s -p -b out seite1.pbm seite2.pbm` erzeugt `out.sym` plus `out.0000` und `out.0001` — genau die Aufrufform, die der Wrapper später verwendet. Zwei ähnliche Seiten (das echte Kuvert-Testbild, Schwellwert 128, zweimal als Seiten kodiert): `out.sym` 10.169 Byte, Seitenströme 2.259 + 2.259 Byte — die Seitenströme zusammen sind deutlich kleiner als die Globals, die Symbole liegen also wirklich im Wörterbuch. Zwei VERSCHIEDENE Seiten (Kuvert- + A4-Testbild): sym 7.207 Byte, Seiten 12.177 + 15.756. Bei zwei verschiedenen Seiten zahlt sich das Wörterbuch also noch nicht aus (Größen siehe unten).

**Determinismus: JA.** Dieselben zwei Seiten, zweimal in verschiedene Basisnamen kodiert, sind in allen drei Dateien bytegleich (`cmp`). Das ist die Voraussetzung für Golden-Master-Tests über sw-PDFs.

**Das Programm und seine Version.** Das Programm heißt `jbig2`, Paket `jbig2`, Quellpaket `jbig2enc`, Ubuntu noble, Sektion universe/utils. Installierte Version 0.29-2.1build1 (gepinnt über den Digest des Dev-Container-Basisimages); `jbig2 -V` meldet `jbig2enc 0.28` — nach **stderr**, Exit-Code 0. Eine naive Prüfung von stdout findet also nichts (wichtig für #143).

**Unser Schwellwert wird nicht übersteuert.** Quelltextprüfung (jbig2enc 0.29, src/jbig2.cc): Bei 1-bit-Eingabe nimmt der Zweig `if (pixl->d > 1) { ... pixThresholdToBinary ... } else { pixt = pixClone(pixl); }` die Seite als Kopie und überspringt das Schwellwertverfahren vollständig. Experiment: Eine Seite mit Schwellwert 128 (BT.601-Luma wie LumaImage, unter dem Schwellwert → schwarz, geschrieben als P4-PBM), kodiert und mit `jbig2dec` 0.20 zurückdekodiert, weicht in 287 von 4.917.744 Pixeln ab = 0,0058 % — dieser Rest ist die verlustbehaftete `-s`-Symbolvereinheitlichung, kein zweiter Schwellwert. Darum übergibt MonochromeStep ein PBM: Nur 1-bit-Eingabe garantiert, dass der Kodierer unsere Pixel verwendet.

**Was es bringt.** Zwei echte Seiten: JBIG2 gesamt 35.140 Byte gegenüber den beiden Graustufen-JPEGs mit 1.264.306 Byte → 2,8 %, also etwa 36× kleiner. Gegenüber der seitenweisen Symbolkodierung derselben zwei Seiten (12.428 + 22.095 = 34.523 Byte) bringt das gemeinsame Wörterbuch bei zwei *verschiedenen* Seiten noch nichts — es zahlt sich erst bei wiederkehrenden Formen aus: Zwei ähnliche (gleiche) Seiten kosten gemeinsam 14.687 Byte gegenüber 2×12.428 = 24.856 einzeln, also 41 % Ersparnis, und genau dieses gemessene Verhältnis verwenden die späteren Tests (Seitenströme zusammen ≈ 44 % der Globals-Größe).

**Die Verlustwarnung.** `-s` (Symbol-Modus) ist verlustbehaftet by design: Ähnliche Symbole werden vereinheitlicht; gemessen 0,0058 % der Pixel an einem echten Scan, für gescannten Text vertretbar. `-r` (Refinement, die verlustlose Variante) ist TOT: Die Quelle kehrt mit 1 und der Meldung `Refinement broke in recent releases since it's rarely used. If you need it you should bug agl@imperialviolet.org to fix it` zurück, bevor das Flag je gesetzt wird — per Experiment bestätigt (Exit 1, keine Ausgabedateien).

**PDF-Modus.** `-p`-Seitenströme tragen keinen JBIG2-Dateikopf (erste Bytes `00 00 00 01 30 00 01 00`, nicht die Magie `97 4A 42 32`) — genau das macht sie in ein PDF einbettbar. jbig2dec kann `-p`-Ströme nicht direkt dekodieren (erwartet, kein Dateikopf); die Pixelprüfung nutzte daher den Dateimodus (Ausgabe nach stdout).

**Randnotiz zur Methode.** Die zwei 1-bit-Seiten entstanden aus den eingecheckten Testbildern `envelope_dl_300dpi_raw.jpg` und `din_a4_300dpi_raw.jpg`: `jpegtran -grayscale`, dann ein Wegwerf-Java-Programm, das die BT.601-Formel von LumaImage `(299R+587G+114B)/1000` nachbildet, Schwellwert 128 (darunter → schwarz), geschrieben als P4-PBM. Davon ist nichts eingecheckt; der Spike checkt nur diese Niederschrift ein.

**Entscheidung: Der Entwurf der Work Orders 7 und 8 (MonochromeStep → PBM, Jbig2Enc mit `-s -p -b`, gemeinsame Globals je Dokument) stützt sich auf diesen Spike und hält.**

## PDF-Engine-Wechsel auf OpenPDF: Determinismus-Mechanismus (Spike #141)

**Byte-identische PDFs sind mit OpenPDF 3.0.5 erreichbar — JA.** Ein zweiteiliges Wegwerf-PDF aus den beiden eingecheckten Testbildern `envelope_dl_300dpi_raw.jpg` und `din_a4_300dpi_raw.jpg` (Seitengröße Pixel ÷ 300 dpi × 72 pt, Bild via `Image.getInstance(bytes)` + `setAbsolutePosition(0,0)` + `scaleToFit`), zweimal mit gepinnter Clock gebaut, war bytegleich (`cmp`). Die Gegenprobe: derselbe Bau OHNE die `/ID`-Festschreibung unterscheidet sich in genau 56 Byte, alle im Trailer-Feld `/ID` — dieses Feld ist das einzige, das sonst variiert.

**Die Nähte (alle vier Felder).** OpenPDF 3.0.5 bildet die Dokument-ID über `PdfEncryption.createDocumentId()` — 16 Byte aus `SECURE_RANDOM` (die Quelle nutzt also nicht mehr Zeit+Speicher, wie im Issue noch vermutet). Aber der Trailer-Schreiber prüft zuerst: `writeTrailer()` liest `getInfo().contains(PdfName.FILEID)` und übernimmt den Wert aus dem Info-Wörterbuch, statt eine neue ID zu erzeugen. Die Naht ist daher `writer.getInfo().put(PdfName.FILEID, PdfEncryption.createInfoId(id, id))` nach `open()`, wobei `id` 16 deterministische Byte aus `clock.millis()` sind. `CreationDate` und `ModDate`: `Document.open()` ruft `addProducer()` und `addCreationDate()` mit der aktuellen Zeit auf; danach überschreiben via `writer.getInfo().put(PdfName.CREATIONDATE, new PdfDate(calendarFromClock))` (`PdfDate` nimmt einen Calendar, kein Date) und ebenso für `MODDATE`. `Producer`: `writer.getInfo().put(PdfName.PRODUCER, new PdfString("UnboundAir"))`. Verifiziert durch Rücklesen des erzeugten PDFs mit PDFBox (dem unabhängigen Leser): Der Trailer-`/ID` trägt genau die 16 Clock-Byte zweimal, `CreationDate = ModDate = D:20260621000000Z`, `Producer = UnboundAir`. Das Info-Wörterbuch trägt dabei den `FileId`-Eintrag als bewussten Teil des Mechanismus — er ist die Festschreibung, kein Zufall.

**Zum Verhältnis zum Vorgänger-Abschnitt:** Der Abschnitt „PDF-Metadaten-Determinismus: injizierbare Clock" beschreibt den PDFBox-Mechanismus (`documentId = clock.millis()`, weil PDFBox `/ID` aus Zeit+Zufall bildet, Producer fest `UnboundAir`). Was hier steht, ersetzt ihn nicht, sondern spiegelt ihn: Der OpenPDF-Mechanismus funktioniert anders (FILEID-Naht statt Setter), die Lehre ist dieselbe. **Aktuell in Kraft ist der OpenPDF-Mechanismus** — der PDFBox-Abschnitt bleibt als Verlauf lesbar, damit das Log ein Log bleibt.

**Die allgemeine Lehre überlebt den Engine-Wechsel.** Die Einsicht des Clock-Abschnitts — eine Engine findet einen Weg, aktuelle Zeit oder Zufall in ein PDF zu legen, und diesen Weg zu finden ist Teil der Arbeit — bestätigt sich hier zum zweiten Mal an einer zweiten Engine. Das ist ausdrücklich festgehalten, weil es beim nächsten Engine-Wechsel wieder gelten wird.

**Rohes JPEG-Einbetten bestätigt.** PDFBox-Rücklesen: Filter `/DCTDecode`, die Byte aus `COSStream.createRawInputStream()` sind bytegleich mit den Quell-JPEGs (373.983 und 1.068.347 Byte). Keine Neukodierung. Alles, was über `java.awt.Image`/`BufferedImage` liefe, ist zu vermeiden (dekodiert + kodiert neu) — der Code-Kommentar in `PdfBuilder` trägt das; hier steht die gemessene Tatsache.

**brotli4j: AUSGESCHLOSSEN.** Gemessen: Die POM von OpenPDF listet `com.aayushatharva.brotli4j:brotli4j:1.23.0` OHNE `<optional>true</optional>` (anders als fop direkt darüber), landet also transitiv auf dem Laufzeit-Classpath (bestätigt via `dependencies --configuration runtimeClasspath`); sie bringt native Bibliotheken mit. ABER `Document.useBrotliCompression` steht auf `false` — Brotli-Kompression der Content-Streams ist ein Opt-in, das wir nie nutzen, und das Spike-PDF baute sich mit fehlendem brotli4j auf dem Classpath problemlos. Entscheidung: Ausschluss in `build.gradle.kts` (`exclude(group = "com.aayushatharva.brotli4j")`) — native Bibliotheken berühren die GraalVM-Option, die der Plan offen hält. Der Ausschluss wird in #145 angewendet; hier ist er mit Begründung entschieden.

**Java 26 / Kotlin 2.4.20.** Der Spike lief auf JDK 26.0.2 (Temurin, der Dev Container) ohne Warnungen. Die Kombination mit Kotlin 2.4.20 ist durch den echten Bau in den Work Orders 5A/5B (#145/#146) belegt — das steht hier schlicht so, statt zu behaupten, es sei in diesem Spike bewiesen.

**Kein Fallback nötig.** Da voller Byte-Determinismus über die FILEID-Naht erreichbar ist, behält die Golden-Master-Schicht ihren schärfsten Test; kein Normalisierungs-Fallback.

## PDF-Engine: OpenPDF statt PDFBox, PDFBox bleibt als Prüfer (Entscheidung)

**Dieser Meilenstein existiert, weil PDFBox keinen JBIG2-Strom einbetten kann.** SV-08 verlangt ein PDF mit einem gemeinsamen Symbolwörterbuch je Dokument — alle Seiten tragen `/JBIG2Decode` und verweisen auf denselben `/JBIG2Globals`-Strom. PDFBox bietet dafür keine Einbettungsmöglichkeit; OpenPDF dagegen liefert `ImgJBIG2` mit und führt in `PdfWriter` eine `JBIG2Globals`-Ablage, die identische Globals in einen einzigen PDF-Strom zusammenführt — genau die Wörterbuchform, die SV-08 braucht.

**Aufgegeben wurde dafür Vertrautes.** PDFBox ist die weiter verbreitete Bibliothek, und seine `JPEGFactory` war eine verstandene Naht: bekannt, wo das rohe JPEG hineingeht und wo Neukodierung droht. Auf der neuen Engine musste der JPEG-Pfad neu verifiziert werden — das ist geschehen (Spike #141, Abschnitt oben: `/DCTDecode`, Rohstrom bytegleich). Der Wechsel kauft die JBIG2-Fähigkeit mit dem Preis einer erneut geprüften JPEG-Einbettung.

**PDFBox bleibt im Test-Scope — als unabhängiger Prüfer, nicht als zweite Engine.** Ein Prüfer, der derselbe Code ist wie der Erzeuger, beweist nichts: OpenPDF, das sein eigenes PDF gegenliest, bestätigt nur sich selbst. PDFBox liest, was OpenPDF geschrieben hat, als unabhängige zweite Implementierung — und `COSStream.createRawInputStream()` ist der einzige Weg zu beweisen, dass ein JPEG ohne Neukodierung im PDF liegt. PDFBox wird als reine TEST-Abhängigkeit geführt (`testImplementation("org.apache.pdfbox:pdfbox:3.0.8")`), der Laufzeit-Classpath bleibt unberührt. Die Dependency-Zeile in `build.gradle.kts` trägt dieselbe Begründung.

**Determinismus: entschieden, nicht neu vermessen.** Der Mechanismus steht im Spike-Abschnitt oben (#141): die FILEID-Naht in `writer.getInfo()`, die Negativkontrolle (ohne Festschreibung genau 56 Byte nur in `/ID`) und der bytegleiche Doppellauf. **Entscheidung: Die Golden-Master-Schicht behält ihren schärfsten Voll-Byte-Vergleich; ein Normalisierungs-Fallback ist nicht nötig.** Auch die brotli4j-Entscheidung (Ausschluss) ist dort bereits mit Begründung entschieden und wird hier nur referenziert, nicht wiederholt.

**Zum Clock-Abschnitt:** Der Abschnitt „PDF-Metadaten-Determinismus: injizierbare Clock" beschreibt den PDFBox-Mechanismus — das ist Geschichte, aber es ist die Geschichte, über die die Anforderung gefunden wurde (jede Quelle von Zufall oder Echtzeit im Schreibpfad muss an die Clock). **Aktuell in Kraft ist der OpenPDF-Mechanismus aus dem Spike-Abschnitt oben.** Der PDFBox-Abschnitt bleibt unverändert lesbar: Ein Entscheidungs-Log, das sich selbst überschreibt, hört auf, ein Log zu sein.

## Modul-Auswahl per Laufzeit, nicht per Build-Verdrahtung (AU-03)

**Entscheidung: Welche Module aktiv sind, steht in `unboundair.output.modules` (Env-Var `UNBOUNDAIR_OUTPUT_MODULES`) als Komma-Liste — in v1 `paperless` — und wird zur Laufzeit ausgewertet; alle Module sind immer registriert. Kein `@ConditionalOnProperty` oder Ähnliches.**

Der Grund ist zweigeteilt. Erstens GraalVM: Spring wertet `@ConditionalOn*` zur Build-Zeit aus, was in Native Images nicht trägt — deshalb verbietet der `ArchitectureRulesTest`-Wächter `@ConditionalOn*` outright, und die Laufzeit-Entscheidung („Laufzeit: normale JVM", Abschnitt oben) verweist genau hierher. Zweitens Offenheit: Die Komma-Liste kostet jetzt nichts und nimmt die offene Frage „mehrere Module gleichzeitig oder immer genau eins?" nicht vorweg — in v1 ist nur ein Wert sinnvoll, aber die Konfiguration muss dafür nicht umgebaut werden.

Die verworfene Alternative wäre **`@ConditionalOnProperty` pro Modul** (idiomatisches Spring, weniger eigene Auswahl-Logik). Ihr Preis wäre eine Architektur, die den späteren Native-Image-Wechsel schon heute verbaut — plus eine Konfiguration, die bei jedem neuen Modul eine neue Bedingung bräuchte, statt eine Liste zu verlängern.

## Outbox: erst persistieren, dann zustellen, dann löschen (AU-04)

**Entscheidung: Ablage unter `unboundair.outbox.path` (Default `/var/lib/unboundair/outbox`); je Dokument ein Unterordner mit `document.pdf` und `metadata.json`; Retry mit exponentiellem Backoff (Start 30 s, Faktor 2, Deckel 1 h, unbegrenzte Versuche); nach erfolgreicher Zustellung wird der Ordner gelöscht.**

Der Reihe nach, jeweils mit dem Grund: Der Pfad liegt unter `/var/lib`, weil die Outbox Zustand ist, der einen Neustart überleben muss — `betrieb.md` legt sie deshalb auf ein persistentes Volume. `metadata.json` wird mitpersistiert, weil Metadaten sonst den Neustart nicht überleben: Ein PDF ohne Scan-Zeitpunkt und Seitenzahl wäre nach einem Absturz ein Waisenkind, das niemand mehr zuordnen kann. Der Backoff (30 s → 1 h, unbegrenzt) behandelt einen Ausfall des Ziels als Normalfall, nicht als Ausnahme: paperless kann Stunden weg sein, und kein Dokument darf deshalb verloren gehen — gelöscht wird erst nach Erfolg.

**Die Backoff-Werte sind Konstruktor-Parameter, keine Einstellungen** (Muster `PageSettings`). Tests kürzen sie darüber ab; `konfiguration.md` wächst nicht um Schrauben, an denen im Betrieb niemand drehen soll. Das ist dieselbe Schnitt-Logik wie im Kern: Was der Betrieb nicht entscheiden muss, wird nicht konfigurierbar getan.

Die verworfene Alternative wäre **direkte Zustellung ohne Persistenz** (einfacher: kein Verzeichnis, kein Backoff, kein Neustart-Pfad). Ihr Preis wäre Datenverlust bei jedem Ausfall zwischen Batch-Schluss und Upload — genau das Fenster, das die Outbox schließt. Oder umgekehrt **Backoff als Konfiguration** (flexibel klingend) — ihr Preis wären Einstellungs-Knöpfe, die niemand begründet drehen kann und deren falsche Werte Dokumente verzögern oder das Ziel fluten.

## Wer die Wiederholung antreibt: passiver Outbox, `OutboxRunner` in `service` (AU-04)

**Entscheidung: Die Outbox ist passiv — reine Logik mit injizierter `Clock`, ohne eigenen Thread. Die Uhr dreht ein `OutboxRunner` in `service`, mit injizierter `Clock` und injiziertem Sleeper, genau wie `ScanLoop` es schon tut.**

Der Grund ist Testbarkeit: Eine Outbox, die sich selbst startet, müsste in jedem Test nebenläufig geprüft werden — Threads, Timing, Flackern. Nebenläufigkeit in Tests war in Meilenstein 3 die Quelle der sporadisch roten Läufe. Passiv heißt: persistieren, fällige Einträge nennen, Erfolg oder Fehlschlag vermerken — alles deterministisch gegen eine gepinnte Clock prüfbar. Der Runner trägt die einzige Schleife, und weil er Clock und Sleeper injiziert bekommt, läuft er im Test gegen gesteuerte Zeit statt gegen die Wanduhr.

Die verworfene Alternative wäre **eine Outbox mit eigenem Thread** (architektonisch „sauber" gekapselt: wer wiederholt, treibt sich selbst an). Ihr Preis wäre Nebenläufigkeit in jedem Outbox-Test — genau die Sorte sporadisch roter Läufe, die Meilenstein 3 gekostet hat — plus eine zweite Thread-Lebenszyklus-Verwaltung neben `ScanLoop`, ohne einen einzigen zusätzlichen Fall abzudecken.

## Dateiname und paperless-Felder: für Menschen, nicht für Maschinen (AU-05)

**Entscheidung: Zeitstempel = Beginn der ersten Seite des Batches, in der lokalen Zeitzone des Containers (über `TZ` steuerbar), Dateiname `scan-YYYYMMDD-HHMMSS.pdf`. `tags`, `correspondent` und `document_type` sind optional als numerische IDs konfigurierbar. `title` und `created` werden nicht gesetzt.**

Der Name ist für Menschen gedacht, nicht für Maschinen: Wer im paperless-Eingang `scan-20260928-143205.pdf` sieht, weiß, welcher Stapel das war — dafür zählt die Ortszeit am Gerät, nicht UTC. Der Batch-Anfang (nicht das Batch-Ende, nicht „jetzt beim Upload") ist der Zeitpunkt, den der Nutzer mit dem Einlegen verbindet; ein Retry Stunden später darf den Namen nicht verändern. Die drei optionalen Felder als numerische IDs entsprechen dem, was die paperless-API erwartet — Namen aufzulösen wäre Aufgabe des Clients, die ihm nicht zusteht.

**Warum `title` und `created` fehlen.** paperless leitet beides aus dem Dokument besser ab, als ein Uploader es raten könnte: Wer `title` setzt, überschreibt die eigene Ableitung mit einer schlechteren; wer `created` setzt, behauptet ein Erstellungsdatum, das der Scan-Zeitpunkt nur ungefähr ist. Weglassen ist hier die bessere Übergabe.

Die verworfene Alternative wäre **UTC-Zeitstempel oder Upload-Zeitpunkt** (maschinen-sauber, zeitzonenfest). Ihr Preis wäre ein Name, der dem Menschen am Gerät nichts sagt — und ein Name, der sich bei jedem Retry ändert, obwohl es dasselbe Dokument ist. Oder **alles setzen, was die API hergibt** (`title`, `created` dazu) — ihr Preis wäre schlechtere Metadaten durch gut gemeinte, aber schlechtere Behauptungen.

## Doppelscan-Schutz: in v1 weggelassen (OF-04)

**Entscheidung: Kein Doppelscan-Schutz in v1 — und was das in der Praxis heißt: Falls das Gerät nach einem Scan noch einmal kurz `scanready` meldet, ohne dass ein neues Blatt eingelegt wurde, erzeugt der Dienst eine einseitige Leerseite als eigenes Dokument.**

Der Grund ist die Leitplanke „nichts am Protokoll erfinden": Ob das Problem überhaupt auftritt, ist nicht gemessen (OF-04). Jede Sperrzeit wäre geraten — und eine geratene Sperrzeit im Dienst ist etwas anderes als eine geratene Meldeschwelle im Messwerkzeug: `measure` meldet Abstände unter 2 Sekunden als „possible double scans" in seiner Zusammenfassung, weil das nur einem Menschen etwas zur Ansicht zeigt und am Verhalten nichts ändert. Im Dienst wäre derselbe Wert eine Verhaltensentscheidung: Echte Seiten, die jemand schnell nachlegt, würden verworfen — ein Datenverlust durch eine Zahl, die niemand gemessen hat.

Die verworfene Alternative wäre **eine Sperrzeit „zur Sicherheit"** (etwa: Scans im Abstand unter N Sekunden verwerfen). Ihr Preis wäre der schlimmste im Projekt: still verworfene echte Seiten, ohne dass ein Log je erklärte, warum ein Blatt fehlt. Liefert die Messung zu OF-04 je den Befund, kommt der Schutz mit gemessener Schwelle — bis dahin bleibt das sichtbare, korrigierbare Übel (eine Leerseite zu viel) dem unsichtbaren (eine Seite zu wenig) vorzuziehen.

## Mehrsprachige Doku: Deutsch als Quelle, Englisch als Hauptsprache (05.10.2026, DO-12 bis DO-16)

**Entscheidung: Verfasst wird auf Deutsch, Hauptsprache ist Englisch. Die Produktdoku liegt zweisprachig unter `docs/de/` und `docs/en/`, die Arbeitsdokumente ziehen nach `docs/internal/` um und bleiben deutsch.**

Bis zum 05.10.2026 stand „Englische Doku" unter „Offene Entscheidungen". Damit ist sie geschlossen — und sie verschiebt eine Bedeutung: „Führend" meinte bisher zwei Dinge zugleich, nämlich *wird verfasst* und *wird gelesen*. Künftig ist Deutsch nur noch die Quelle, Englisch die veröffentlichte Fassung. Ohne diese Trennung widerspricht sich die Regel selbst, sobald eine zweite Sprache dazukommt.

**Die Dreiteilung ist gemessen, nicht geschätzt.** Am 05.10.2026 ergab `git log --follow`:

| Gruppe | Umfang | Commits |
|---|---|---|
| 9 Produktdateien | 78 KB | 46 |
| 4 Arbeitsdokumente | 152 KB | 61 |
| davon `plan.md` allein | 46 KB | **29** |

Die Arbeitsdokumente sind also doppelt so groß und ändern sich häufiger als die Produktdoku. Sie mitzuübersetzen verdreifacht die Übersetzungsmenge an genau den Dateien, die am meisten wackeln.

Entscheidend ist aber nicht die Menge, sondern ein **Regelkonflikt**: `AGENTS.md` verlangt „Erst Plan, dann Verhalten" — jede Verhaltensänderung beginnt mit einer Planänderung. DO-15 macht eine nicht nachgezogene Übersetzung rot. Zusammen heißt das: kein Zugriff auf `plan.md` ohne vorherige Übersetzungsrunde, 29-mal in der bisherigen Projektgeschichte, jeweils *bevor* überhaupt Code entsteht. Der Wächter, der die Doku ehrlich halten soll, würde zur Bremse an der empfindlichsten Stelle. Dazu: `CONTRIBUTING.md` sagt „bei Widersprüchen gilt der Plan" — bei zwei Fassungen wäre die Rückfrage, *welcher*. Ein Auftrag in zwei Sprachen braucht eine Vorrangklausel, die nichts einbringt, solange der Auftraggeber Deutsch spricht. `entscheidungen.md` ist ein Logbuch der Vergangenheit: fortschreiben, nicht nachübersetzen.

`teststrategie.md` und `offene-fragen.md` sind der Grenzfall und bleiben bewusst mit offener Tür: Auf beide verweist Produktdoku (`CONTRIBUTING.md` bzw. `protokoll.md` und `hardware.md`), ein englischsprachiger Leser landet dort also im Deutschen. Das wird mit `(German only)` am Link sichtbar gemacht, statt es zu verschweigen. Übersetzt werden können sie später — Starlight trägt fehlende Seiten je Sprache ohne Umbau.

Die verworfene Alternative wäre **alles übersetzen** (ehrlich, keine Sprachlücken). Ihr Preis wären 230 KB statt 78 KB Erstübersetzung und der Regelkonflikt oben bei jeder Planänderung. Oder **nichts übersetzen** und bei Deutsch bleiben — das war der Stand und schließt jeden aus, der kein Deutsch liest, obwohl Code, Issues und Commits längst englisch sind.

## Doku-Seite: Astro Starlight, statisch gebaut, im selben Repository

**Entscheidung: Astro Starlight als Generator, Static Site Generation in CI, Veröffentlichung auf GitHub Pages, Konfiguration unter `site/` im selben Repository.**

**Warum statisch (SSG) und nicht serverseitig (SSR).** Doku ist für jeden Besucher gleich: kein Login, keine Nutzerdaten, nichts, was pro Anfrage zu entscheiden wäre. Statisch heißt deshalb: kein Server, keine Laufzeitkosten, kein Angriffsziel, nichts, was nachts ausfällt. SSR würde einen Server betreiben, der bei jeder Anfrage dasselbe Ergebnis neu ausrechnet.

**Warum nicht mit Kotlin und Spring**, obwohl der Stack hier liegt: Technisch ginge es (flexmark plus Thymeleaf). Der aufschlussreichste Gegenbeweis ist, dass **Spring es selbst nicht so macht** — Spring Boot baut seine Doku seit 3.3 mit Antora, einem Node-Werkzeug, und veröffentlicht statisch auf `docs.spring.io`. Dazu käme Eigenbau von Volltextsuche, Sprachumschalter, Navigation, Syntax-Highlighting, Dark Mode und Mobilansicht — jedes einzeln Kleinarbeit, zusammen Wochen. Und es widerspräche dem Plan: „eine Anwendung, Kern schlank, Native Image offenhalten". Ein Markdown-Renderer im Scanner-Dienst ist genau das nicht. Der Dienst läuft am Scanner-WLAN, die Doku soll im Internet erreichbar sein — zwei Dinge mit zwei Lebensdauern.

**Warum Starlight** unter den Kandidaten (Stand 2026): i18n ist Kern und nicht Anbau — Sprachumschalter, Sidebar je Sprache und, der eigentliche Gewinn, automatischer Rückfall auf die Hauptsprache bei fehlender Übersetzung samt Markierung. Damit muss nie jede Sprache gleichzeitig vollständig sein, was die Dreiteilung oben überhaupt erst tragfähig macht. Suche ist eingebaut (Pagefind, läuft im Browser, je Sprache getrennt) — kein Algolia-Konto, kein weiterer Dienst. Die Standardoptik trägt ohne eine Zeile CSS, und im Normalfall wird kein JavaScript ausgeliefert.

Verworfen: **Docusaurus** (größeres Ökosystem, aber React-Laufzeit und Suche nur über Algolia), **Material for MkDocs** (solide, i18n über Plugin — die nächstbeste Wahl, falls Python dem Projekt näher wäre als Node), **Antora** (stark bei versionierter AsciiDoc-Referenz, schwach bei i18n, nüchterne Optik).

**Der Preis, offen benannt:** Node kommt ins Projekt. Begrenzt auf `site/`, nur in CI und beim Schreiben der Doku — Gradle, Dev Container und das `arm64`-Runtime-Image bleiben unberührt.

**Warum ein Repository und kein eigenes Docs-Repo.** Doku und Code ändern sich gemeinsam; der Plan verlangt es mit „Erst Plan, dann Verhalten". Eine neue Einstellung heißt neue Zeile in `configuration.md` — im selben PR, im selben Diff. Bei zwei Repos sind das zwei PRs, und einer wird vergessen. Dazu bräuchte es eine Brücke (Submodul oder ein Workflow mit Schreibrecht im anderen Repo), die 85 relativen Links im Repository würden über Repo-Grenzen hinweg ins Leere zeigen, und der „diese Seite bearbeiten"-Link funktioniert nur innerhalb eines Repositorys ohne Zusatzaufwand. Ein eigenes Docs-Repo lohnt, wenn mehrere Produkte eine Seite speisen oder Redakteure ohne Code-Zugriff arbeiten — beides trifft hier nicht zu.

**Zur Web-UI, die perspektivisch kommt:** Doku-Seite und Web-UI bleiben getrennt. Die Doku ist öffentlich, statisch und für alle gleich; die Web-UI läuft beim Betreiber, zeigt dessen Scans und hat Zustand. Die UI kann aus einem Hilfe-Symbol auf die Doku-Seite verlinken — das genügt.

## Übersetzung: lokales LLM auf Abruf, CI prüft nur (DO-14, DO-15)

**Entscheidung: Übersetzt wird lokal auf Abruf mit einem LLM über LM Studio — Erstmodell Gemma 4 26B, benannter Rückfallweg Qwen3.6 27B. CI übersetzt nie, sie prüft nur. Skript, Glossar und Prompt liegen versioniert im Repository.**

Zuerst war ein Dienstanbieter vorgesehen (DeepL, in CI, Glossar beim Anbieter). Das entfiel aus einem Grund, der die Entscheidung gleich mitbegründet: **der kostenlose API-Plan wurde eingestellt.** Genau diese Abhängigkeit — ein Dritter ändert seine Bedingungen, und die Doku-Pipeline steht — spricht für die lokale Lösung. Dazu kommen: kein Secret im Repository (passend zur Leitplanke „Keine Secrets im Repository"), kein Monatslimit, keine Daten, die das Haus verlassen.

**Warum auf Abruf und nicht in CI.** Ein GitHub-Actions-Runner läuft in der Cloud und erreicht ein lokales LM Studio nicht — ohne Tunnel, der das Modell ins Internet stellen würde. Die Übersetzung wandert damit zum Entwickler, und das ist kein Verlust: Der Prüfschritt ist dadurch nicht mehr aufschiebbar, weil der Entwurf ohnehin durch die Hände eines Menschen geht.

**Die Arbeitsteilung ist der Kern:** Das LLM übersetzt (lokal, auf Abruf, Ergebnis immer geprüft). CI rechnet nach (Struktur, Aktualität, Links — ohne LLM). CI kann nicht übersetzen, aber sehr wohl feststellen, ob eine Übersetzung formal stimmt und zum aktuellen deutschen Stand gehört. Bedeutung beurteilt ein Mensch. Damit wird aus „die Übersetzung sollte gepflegt werden" eine prüfbare Regel — dasselbe Muster wie beim ArchUnit-Wächter.

**Das Risiko, offen benannt: ein LLM erfindet Dinge, ein Übersetzungsdienst seltener.** Bei `protocol.md` mit Byte-Sequenzen, Füllbytes und Firmware-Versionen ist das genau der Fehler, den die Leitplanke „Nichts am Protokoll erfinden" verhindern soll. Gegenmittel sind drei: der Prompt nagelt fest, was unangetastet bleibt (Code-Blöcke, Pfade, Property-Namen, Anforderungs-IDs, Byte-Werte, Tabellenstruktur, Linkziele); der Strukturprüfer aus DO-15 macht jede Abweichung daran rot; und die Durchsicht durch einen Menschen ist Pflicht, nicht Empfehlung.

**Warum das Glossar ins Repository gehört** und nicht zu einem Anbieter: Es ist der Teil, der über die Qualität entscheidet, und es muss versioniert sein, sonst übersetzt jeder Lauf anders. Ohne Festlegung wird aus „Senke" mal *sink*, mal *drain* — ein Begriff, der allein im Plan an elf Stellen steht. Festgelegt sind unter anderem: Senke → sink, Zuschnitt → crop, Blatt → sheet, Seite → page, Dienst → service, Wächter → guard, Abnahme → acceptance criterion, Zeitfenster → batch timeout window.

**Warum Gemma 4 26B,** ohne vergleichenden Spike: Der Auftraggeber hat das Modell gesetzt. Die Gemma-Reihe ist traditionell stark mehrsprachig, und die Größen passen — die umfangreichste Produktdatei (`ausgabe-module.md`, 13 KB, rund 3.500 Token) geht in einem Durchgang durch. Reicht die Qualität bei `protocol.md` nicht, ist **Qwen3.6 27B** der benannte Rückfallweg; der Wechsel wird dann hier mit Begründung festgehalten, statt still zu passieren.

**Warum das Skript ins Repository darf,** obwohl „Werkzeug-Konfiguration gehört nicht ins Repository" gilt: Prompt und Glossar sind nicht Werkzeugeinrichtung, sondern Projektsubstanz — ohne sie übersetzt jeder Lauf anders, wie bei den Workflow-Dateien, die ebenfalls im Repository liegen. Zwei Bedingungen halten die Regel gewahrt: keine fest verdrahtete Adresse (Endpunkt und Modell kommen aus `UNBOUNDAIR_DOCS_LLM_URL` und `UNBOUNDAIR_DOCS_LLM_MODEL`, wer kein LM Studio hat setzt etwas anderes ein oder übersetzt von Hand), und das Skript ist nie Teil von `build`, sondern läuft auf Abruf wie der Mutationslauf.

## Spike E (Starlight-Inhaltsquelle, #227)

Stand: 06.10.2026, Astro 7.3.5, `@astrojs/starlight` 0.42.5, per pnpm installiert. Gerüst: zwei deutsche Dateien und eine englische, gebaut mit `./node_modules/.bin/astro build`. Das Gerüst ist danach gelöscht — die echte Seite kommt erst mit WO 4.

**Ohne `src/content.config.ts` gibt es keine Sammlungen.** Der erste Bau meldete `The collection "docs" does not exist or is empty` und erzeugte nur `404.html`. Nötig ist:

```ts
import { defineCollection } from 'astro:content';
import { docsLoader, i18nLoader } from '@astrojs/starlight/loaders';
import { docsSchema, i18nSchema } from '@astrojs/starlight/schema';

export const collections = {
  docs: defineCollection({ loader: docsLoader(), schema: docsSchema() }),
  i18n: defineCollection({ loader: i18nLoader(), schema: i18nSchema() }),
};
```

Die Warnung zur Sammlung `i18n` (`base directory .../i18n/ does not exist`) ist folgenlos, solange keine UI-Texte überschrieben werden.

**1. Inhaltswurzel umleiten: per Option nein, per Symlink ja.** `docsLoader()` nimmt in 0.42.5 nur `generateId` entgegen — kein `base`, kein `pattern`. Die Zeile `Loads content files from the src/content/docs/ directory` in `loaders.d.ts` ist wörtlich zu nehmen: Der Pfad ist fest verdrahtet. Verzeichnis-Symlinks je Sprache (`src/content/docs/de -> ../../../docs/de`, ebenso `en`) werden dagegen vom Content-Layer-Glob gefolgt: Derselbe Stand einmal als echte Dateien, einmal als Symlinks gebaut, ergab dieselben vier Seiten (`/de/operations`, `/de/configuration`, `/en/operations`, plus 404). Entscheidung für WO 4: Symlinks je Sprache, kein Kopierschritt, keine Dateien unter `site/`. Das Markdown bleibt, wo Beitragende und `git log` es erwarten.

**2. Frontmatter: `title:` ist Pflicht, und es genügt.** Eine Datei ohne Frontmatter bricht den Bau hart ab (`InvalidContentEntryDataError: docs → en/operations data does not match collection schema`). Alle Spike-Dateien trugen nur `title:` und bauten grün — mehr braucht der Übersetzungs-Prompt nicht zu wissen, weniger geht nicht.

**3. Sprachpaarung über den Dateinamen: ja.** `de/operations.md` und `en/operations.md` wurden als dieselbe Seite in zwei Sprachen erkannt (`/de/operations`, `/en/operations`).

**4. Rückfall bei fehlender Übersetzung: ja, aber die Richtung hängt an `defaultLocale`.** Mit `defaultLocale: 'de'` bekam die nur deutsch vorhandene Seite eine englische Rückfall-URL (`/en/configuration/`), die den deutschen Inhalt mit sichtbarer Markierung zeigt („This content is not available in your language yet."), Bau grün. Mit `defaultLocale: 'en'` entstand keine Rückfallseite (englisch 404). Der Rückfall zeigt also Inhalt der Default-Sprache auf der URL der fehlenden Sprache — Vorgabe für WO 4, welche Sprache Default wird.

**5. Drittparteien-Kontakt (Nachtrag aus der Datenschutzprüfung):** `grep` über `dist/` fand kein einziges `https://` in `src=`, `href=` oder `url(`. Alle geladenen Mittel (`script`, `link`, `img`) zeigen auf `/_astro/*` oder `/favicon.svg` — gleiche Herkunft. Die übrigen `https://`-Zeichenketten liegen ausschließlich in JS-Bündeln (Pagefind-Übersetzervermerke u. Ä.), kein dynamischer Import, kein `fetch` auf Fremdhosts. Die Rückfallseite bringt nichts Externes mit — sie nutzt dasselbe Layout mit denselben lokalen Mitteln.

## Spike-Ergebnisse (Zusammenfassung)

- **Spike A (kotest-property auf JUnit Platform 6):** läuft. 1 Test, 0 Failures auf Platform 6.0.3 (Spring Boot 4.1.1, `junit-jupiter` 6.0.3).
- **Spike B (Mutation):** PIT funktioniert (Zahlen oben), kein Fallback nötig.
- **Spike C (jbig2enc, #140):** Gemeinsames Symbolwörterbuch bestätigt, byte-deterministisch, Version jbig2 0.29-2.1build1 / Programm meldet jbig2enc 0.28, auf zwei echten Seiten 36× kleiner als die Grau-JPEGs; -r ist tot, -s verlustbehaftet mit 0,0058 % Pixeln.
- **Spike D (OpenPDF-Determinismus, #141):** byte-identische PDFs erreichbar; Naht ist `PdfWriter.getInfo()` (FILEID/CreationDate/ModDate/Producer); nur `/ID` variiert sonst; brotli4j wird ausgeschlossen (Default aus, native Libs); JPEG roh bestätigt via PDFBox-Raw-Stream.
- **Spike E (Starlight-Inhaltsquelle, #227):** `docsLoader()` kennt kein `base` — Umleitung per Symlink je Sprache (vom Glob gefolgt, identischer Bau); `title:` Pflicht und genügend; Paarung über Dateinamen bestätigt; Rückfall mit Markierung hängt an `defaultLocale`; kein Drittparteien-Kontakt in `dist/`.
