---
title: Entwicklung
---


Wie man `UnboundAir` baut und testet. Gearbeitet wird ausschließlich im Dev Container – auf dem Rechner selbst muss außer einer Container-Runtime und dem Dev-Container-Tooling nichts installiert sein, insbesondere kein JDK und kein Gradle.

## Voraussetzungen

- Eine Container-Runtime (z. B. Podman oder Docker)
- Dev-Container-Unterstützung: entweder die Erweiterung „Dev Containers" in VS Code oder die `devcontainer`-CLI
- Git

Mehr nicht. Das JDK, `jpegtran` und `jbig2` bringt der Container mit; Gradle lädt der Wrapper beim ersten Lauf selbst herunter.

## Dev Container starten

Repository klonen und im Dev Container öffnen. In VS Code: Ordner öffnen, dann „Reopen in Container". Mit der CLI:

```bash
devcontainer up --workspace-folder .
```

Ohne `--workspace-folder` nimmt die CLI das aktuelle Verzeichnis – im Repository genügt also `devcontainer up`.

Wer Podman statt Docker verwendet, hängt `--docker-path podman` an – die CLI sucht sonst nach einer ausführbaren Datei namens `docker` und bricht mit `spawn docker ENOENT` ab:

```bash
devcontainer up --workspace-folder . --docker-path podman
```

### Arbeiten aus einem Git-Worktree

Das Repository lässt sich auch aus einem [Worktree](https://git-scm.com/docs/git-worktree) heraus im Dev Container bauen; der Ordner muss dafür nicht „UnboundAir" heißen. Die CLI hängt den Arbeitsordner unter `/workspaces/<Ordnername>` ein, und `devcontainer.json` leitet `workspaceFolder` aus demselben Namen ab.

Eine Einschränkung gibt es: **Git-Befehle funktionieren im Dev Container nur im normalen Klon, nicht im Worktree.** Ein Worktree enthält statt eines `.git`-Verzeichnisses nur eine Datei, die auf das gemeinsame Git-Verzeichnis des Hauptklons zeigt – und das liegt außerhalb des eingehängten Ordners. Zum Bauen und Testen spielt das keine Rolle: Der Gradle-Build braucht kein Git. Git-Befehle gehören ohnehin neben den Container, nicht hinein.

Die `devcontainer`-CLI kann das gemeinsame Git-Verzeichnis mitmounten (`--mount-git-worktree-common-dir`), verlangt dafür aber mit relativen Pfaden angelegte Worktrees (`git worktree add --relative-paths`, ab Git 2.48). Siehe OF-12 in `docs/internal/offene-fragen.md`.

Beim ersten Start wird das Image gebaut, das dauert einige Minuten. Die Ausgabe wirkt dabei streckenweise wie eingefroren, weil die Fortschrittsanzeige der Container-Runtime gepuffert durchgereicht wird. `--log-level debug` zeigt stattdessen jeden Schritt einzeln.

Danach prüfen, ob die Umgebung stimmt:

```bash
java -version      # erwartet: Temurin, Version 26
jpegtran -version  # erwartet: eine libjpeg-turbo-Version
jbig2 -V           # erwartet: eine jbig2enc-Version; schreibt auf stderr, Exit 0
```

Alle drei müssen antworten. `jpegtran` ist keine Kür: Zuschnitt und Graustufen-Umwandlung laufen ausschließlich darüber, ohne das Programm schlagen die entsprechenden Tests fehl. Dasselbe gilt für `jbig2`: die 1-bit-Kodierung des `bw`-Modus (SV-08) läuft ausschließlich darüber, ohne das Programm schlagen `Jbig2EncTest`, `PdfBuilderJbig2Test` und `PdfBwGoldenTest` fehl.

### Warnung beim Auflösen des Image-Namens

Das Basis-Image ist im Dockerfile per Digest festgenagelt. Die `devcontainer`-CLI kann die Schreibweise `name:tag@sha256:…` in ihrer Vorab-Prüfung nicht verarbeiten und meldet:

```
Path 'library/eclipse-temurin:26-jdk-noble' for input '…@sha256:…' failed validation.
Error fetching image details: Could not parse image name '…'
```

Das ist folgenlos: Die Meldung stammt aus einer Metadaten-Abfrage der CLI, nicht aus dem Build. Die Container-Runtime versteht den Digest und zieht das Image korrekt.

## Bauen und testen

Alles im Dev Container ausführen:

```bash
./gradlew build           # kompilieren, Linter, Tests
./gradlew test            # nur Tests
./gradlew spotlessCheck   # nur Linter
./gradlew spotlessApply   # Formatierungsmängel automatisch beheben
```

`spotlessCheck` hängt an der `check`-Task und läuft damit bei `build` automatisch mit. `spotlessApply` ändert Dateien – bewusst einsetzen, nicht nebenbei.

Geprüft wird mit **ktlint**; Spotless ist nur der Rahmen, der es startet. Warum dieser Umweg nötig ist, steht in `docs/internal/plan.md` unter „Entschieden – nicht mehr offen" und in `docs/internal/offene-fragen.md` unter OF-11.

Die Tests kommen ohne echte Geräte und ohne fremde Dienste aus: Der Scanner wird durch einen Fake-Scanner ersetzt, der im Test als TCP-Server läuft und das Verhalten des echten Geräts nachbildet – inklusive seiner Eigenheiten wie der Füllbytes in den Antworten.

Eine Netzwerkverbindung braucht trotzdem, wer zum ersten Mal baut: Der Wrapper lädt die Gradle-Distribution, Gradle lädt die Abhängigkeiten. Beides landet im Cache und wird danach nicht mehr benötigt.

**Der echte Scanner wird nie für Tests verwendet.** Er ist nur nach ausdrücklicher Freigabe und nur für Messläufe (`measure`) im Spiel.

## Mutationstest (`pitest`)

Zusätzlich zu den gewöhnlichen Tests gibt es einen Mutationslauf: Er verändert den Produktivcode an vielen Stellen minimal und prüft, ob die Tests das merken. Das deckt schwache Zusicherungen auf, die eine reine Zeilenabdeckung nicht zeigt. Die Schicht ist in `docs/internal/teststrategie.md` unter „Mutation" beschrieben.

```bash
./gradlew pitest          # Mutationslauf über die Kern-Pakete
```

Vier Dinge, die man vorher wissen sollte:

- **Nicht Teil von `build`.** Der Task hängt bewusst nicht an `check` oder `build` – er läuft nur, wenn man ihn ausdrücklich aufruft. Ziel sind die Kern-Pakete `scanner`, `image`, `processing`, `output` (mit `output.outbox` und `output.paperless`) und `service` – die Liste in `build.gradle.kts` und `docs/internal/teststrategie.md` ist maßgeblich.
- **Er dauert.** Rund **2,5 Stunden** beim vollen Lauf über alle sieben Pakete, weil die zeitgesteuerten Scanner-Tests für jede Mutation erneut laufen. Ein Lauf über ein einzelnes Paket (z. B. nur `processing`) dauert dagegen nur Sekunden. Der Bericht landet in `build/reports/pitest/index.html`.
- **Der erste Lauf braucht Netz.** Die `org.pitest`-Artefakte liegen nicht im normalen Abhängigkeits-Cache, weil sie nur dieser Task verwendet. `--offline` schlägt deshalb beim ersten Mal fehl. Das berührt DC-03 nicht: Die Anforderung gilt `./gradlew test`, und der bleibt offline.
- **Er braucht Speicher.** Gradle-Daemon, Kotlin-Daemon und die PIT-Prozesse liegen gleichzeitig im RAM. Auf einem kleinen Container-Host kann der Gradle-Daemon dabei abstürzen („daemon disappeared"). Bricht ein Lauf ab, bleibt der PIT-Hauptprozess verwaist zurück und startet weiter Unterprozesse – er blockiert dann den nächsten Lauf. Vorher aufräumen:

  ```bash
  ./gradlew --stop && pkill -f MutationTestMinion; pkill -f pitest-command-line
  ```

Es gibt eine **Schwelle**: Fällt die Mutationsabdeckung unter den in `build.gradle.kts` gepinnten Wert, schlägt der Task fehl. Der Wert ist der zuletzt gemessene Stand und wirkt als Boden – er wird angehoben, wenn der Score steigt, und nicht stillschweigend gesenkt. Die aktuellen Zahlen je Paket stehen in `docs/internal/entscheidungen.md`.

### Lieber auf Abruf in CI laufen lassen

Wegen der 2,5 Stunden und des Speicherbedarfs muss der volle Lauf nicht am Entwicklungsrechner hängen: Der Workflow `.github/workflows/pitest.yml` führt ihn auf GitHub Actions aus. Er startet **nur von Hand** (`workflow_dispatch`), an keinem `push` – ein Lauf, der bei jedem Commit 2,5 Stunden kostet, wäre schnell abgeschaltet.

```bash
gh workflow run pitest --ref dev
```

Oder im Reiter „Actions" den Workflow `pitest` wählen und als Branch `dev` angeben. Dass die Workflow-Datei auch auf `main` liegt, ist kein Versehen: Die Actions-Oberfläche bietet zum manuellen Start nur Workflows des Default-Branches an. Laufen soll er trotzdem auf `dev` – dem Integrations-Branch (siehe `docs/internal/plan.md`, „Git-Ablauf").

Vier Punkte dazu:

- **Es ist derselbe Dev Container.** Der Workflow baut das Image aus `.devcontainer/Dockerfile` und führt `./gradlew pitest` darin aus. Damit messen CI und lokaler Lauf in derselben Umgebung – gleiche JDK-, `jpegtran`- und `jbig2`-Versionen. Eine runner-seitige Nachbildung wäre eine zweite Wahrheit über die Entwicklungsumgebung.
- **Nativ auf beiden Architekturen**, wie bei der Imageprüfung: `ubuntu-24.04-arm` und der Standard-x86-Runner. Die Golden-Dateien sind gegen die Binärprogramme beider Architekturen geprüft — die CI-01-Matrix fährt beide nativ, Befund: kein Unterschied.
- **Der Bericht ist das Ergebnis.** Er liegt nur auf dem Runner, deshalb lädt der Workflow `build/reports/pitest/` als Artefakt `pitest-report` hoch – aufbewahrt 90 Tage, das ist das Maximum bei GitHub. Ohne diesen Schritt bliebe von 2,5 Stunden nur grün oder rot übrig.
- **Ein Lauf zur Zeit.** Ein zweiter Start ersetzt einen laufenden (`concurrency` mit `cancel-in-progress`), statt parallel weitere 2,5 Stunden zu verbrennen.

Die Neueinmessung der Schwelle (TE-04) gehört damit in CI, nicht auf den Entwicklungsrechner. Die gemessenen Zahlen wandern von dort nach `docs/internal/entscheidungen.md` – mit Datum und Commit, wie bisher.

## Kontinuierliche Integration

Was die Automatik tut – und was sie von dir erwartet. Einzelheiten stehen in den Workflow-Dateien selbst; diese Datei beschreibt nur, womit du rechnen musst.

**Bei jedem Pull Request gegen `dev`** (`.github/workflows/test.yml`) läuft der volle `./gradlew build` – Suite plus Linter – **im Dev-Container-Image**, auf beiden Architekturen (`linux/arm64` nativ, `linux/amd64` nativ). Grün auf beiden ist Pflicht für die Zusammenführung (Regelsatz auf `dev`). Der Checkout holt die volle Historie (`fetch-depth: 0`): Der Übersetzungswächter vergleicht Commit-Stände, und ohne Historie hielte er jede Übersetzung für aktuell — wer den Workflow als Vorlage nimmt, lässt das stehen.

**Beide Architekturen fahren die volle Suite**, weil auf beiden entwickelt wird: Grün heißt auf beiden Maschinen dasselbe. Die Golden-Dateien sind gegen die Binärprogramme beider Architekturen geprüft.

**Nachts auf `dev`** (`.github/workflows/nightly.yml`) läuft dieselbe Suite plus der Image-Bau beider Architekturen; was dabei geprüft wurde, landet in `ghcr.io/digiwomb-dev/unboundair` — `:nightly` als Multi-Architektur-Index, dazu `:nightly-arm64` und `:nightly-amd64`. Nie `latest`, nie eine Version. Ob die Nacht grün war, zeigt das Badge in `README.md`.

**Image-Bau und -Prüfung** (`.github/workflows/image.yml`) bauen beide Images und fahren darin den CT-01-Satz (`jpegtran` da, `jbig2` da, `status` erreicht den Fake-Scanner) — nativ je Architektur, nie unter Emulation: Unter QEMU kann ein Bau grün werden, während das Echte scheitert.

**Ein Release schneiden** (`.github/workflows/release.yml`), in dieser Reihenfolge: `version` in `build.gradle.kts` per Pull Request auf `dev` anheben; `dev` nach `main` zusammenführen; dieses Commit auf `main` mit exakt dieser Version taggen — strikt SemVer, ohne `v` (`1.2.0`, nicht `v1.2.0`). Ein falscher Tag, ein vom Build abweichender Tag oder ein Tag abseits von `main` scheitert laut mit Begründung; der schlechte Tag wird von Hand gelöscht, nicht vom Workflow. Das Release entsteht als Entwurf mit allen Anhängen und wird zuletzt öffentlich — danach ist es unveränderlich. `0.y.z` und Vorabversionen tragen das Vorab-Kennzeichen.

**Lokal bleibt alles fahrbar:** Der Mutationslauf startet weiter auf Abruf, und die Wächter laufen vor dem Pushen — Übersetzung und Deployment-Beispiel in `./gradlew test`, das Drittparteien-Tor per `npm run check` gegen `site/dist/`.

## Laufzeit-Image bauen

Das Laufzeit-Image steht in `Dockerfile` im Repository-Stamm. Gebaut wird es aus dem Repository heraus – vorher das Jar erzeugen, weil das Dockerfile `build/libs/unboundair.jar` hineinkopiert (kein Gradle im Image-Build):

```bash
./gradlew bootJar
docker build -t <name> .
```

`<name>` ist ein Platzhalter: Image-Name und Registry sind noch nicht vergeben, es gibt bewusst keinen festen Namen.

Die Abnahme des Images (CT-01) läuft nicht lokal, sondern auf dem GitHub-Actions-Runner `ubuntu-24.04-arm` im Workflow `.github/workflows/image.yml`. Der Grund ist schlicht: Eine `x86_64`-Maschine ohne QEMU kann ein `linux/arm64`-Image weder bauen noch betreten, also findet die Prüfung dort statt, wo native `arm64`-Hardware vorhanden ist. Der Workflow baut das Image für `linux/arm64` und führt darin die drei Prüfungen aus (`jpegtran` vorhanden, `jbig2` vorhanden, `status` erreicht den Scanner).

Der Betrieb des fertigen Images – wohin es gehört, wie es läuft – steht in `docs/de/operations.md`, nicht hier.

## Fake-Scanner als Prozess

Der Gradle-Task `./gradlew fakeScanner` startet den Fake-Scanner als eigenen Prozess – denselben Fake-Scanner, den die Tests sonst als TCP-Server im Testprozess einbetten. Nützlich, sobald man die CLI von Hand gegen ein Gerät ausprobieren will, ohne den echten Scanner anzufassen:

```bash
./gradlew fakeScanner
```

Der Fake-Scanner hört per Default auf Port **2323**; mit `-PfakeScannerPort=<n>` lässt sich der Port ändern. In einem zweiten Terminal lässt sich dann `status` gegen das Fake-Gerät üben:

```bash
java -jar build/libs/unboundair.jar status --host 127.0.0.1 --port 2323
```

(Das Jar dafür vorher mit `./gradlew bootJar` bauen.)

## Gradle-Wrapper

Der Wrapper gehört mit ins Repository, inklusive `gradle-wrapper.jar`. Die Datei stammt aus der offiziellen Gradle-Veröffentlichung; ihre Prüfsumme ist vorab gegen die von Gradle publizierte Angabe abgeglichen worden:

| | Wert |
|---|---|
| Gradle-Version | 9.7.1 |
| SHA-256 des `gradle-wrapper.jar` | `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |
| Quelle der Prüfsumme | `https://services.gradle.org/versions/all`, Feld `wrapperChecksum` |

Beim Anheben der Gradle-Version ist diese Prüfsumme mitzuführen und erneut abzugleichen. Nachprüfen lässt sie sich, sobald die Datei da ist:

```bash
sha256sum gradle/wrapper/gradle-wrapper.jar
```

## Dokumentationsseite

Die Doku steht auch als gerenderte Seite bereit (deutsch und englisch): https://digiwomb-dev.github.io/UnboundAir/

Lokal bauen (Node bleibt auf `site/` beschränkt — Gradle, Dev Container und Laufzeit-Image berührt das nicht):

```bash
cd site
npm ci --ignore-scripts
npm run build   # Ergebnis in site/dist/
```

Das Tor aus DS-01 läuft lokal mit `npm run check` gegen `site/dist/`. Ohne das Secret `IMPRINT_BLOCK` (nur in CI gesetzt, nie im Repository) schlägt die Imprint-Prüfung fehl — wie bei Fork-PRs, deren Bau nie deployt.

Wie die Seite entsteht und was dabei aus Datenschutzgründen gilt, steht in `docs/internal/entscheidungen.md` (Abschnitte zur Doku-Seite und zur Übersetzung).

## Zusammenarbeit am Repository

Wie Issues benannt und gefiltert werden, wie Commits und Pull Requests aussehen und welche Leitplanken gelten, steht in [`CONTRIBUTING.md`](../../CONTRIBUTING.de.md).

## Warum der Umweg über den Dev Container

Der Container enthält dieselben Systemabhängigkeiten wie das spätere Laufzeit-Image: dieselbe JDK-Hauptversion, dasselbe `jpegtran`, dasselbe `jbig2`. Das steht so auch im `Dockerfile` des Laufzeit-Images, und die beiden Dateien (`.devcontainer/Dockerfile`, `Dockerfile`) gehören zusammen: Wer eine von beiden ändert, prüft die andere mit. Driften die beiden auseinander, laufen die Tests grün und der Dienst fällt im Betrieb um. Deshalb gilt: gebaut und getestet wird im Container, nicht daneben.

### Bekannte Eigenheiten

- Die `devcontainer`-CLI ruft fest `docker` auf. Mit Podman muss `--docker-path podman` mitgegeben werden, sonst bricht sie mit `spawn docker ENOENT` ab.
- Die Ausgabe langer Läufe wirkt eingefroren, weil Fortschrittsanzeigen gepuffert durchgereicht werden. `--log-level debug` zeigt die einzelnen Schritte.
- Der Digest-Pin des Basis-Image erzeugt eine Warnung der CLI („Could not parse image name"). Folgenlos, siehe oben.
- Läuft der Dev Container in einer Umgebung, die selbst nur eine Benutzerkennung kennt (verschachtelte Container ohne eigene UID-Bereiche), schlägt jedes Ändern von Dateibesitz fehl. Das `Dockerfile` ist darauf eingestellt: Der Download-Sandkasten von `apt` läuft als `root`, und das `chown` auf das Gradle-Verzeichnis darf fehlschlagen – nötig ist es dort ohnehin nicht, weil alle Dateien derselben Kennung gehören.

## Einstieg für eine neue Arbeitssitzung

Wer hier neu dazukommt, liest in dieser Reihenfolge:

1. [`CONTRIBUTING.md`](../../CONTRIBUTING.de.md) – die Regeln: Sprache, Commits, Git-Ablauf, Issue-Konvention, Leitplanken
2. `docs/internal/plan.md` – Auftrag, feste Entscheidungen, Anforderungen mit IDs
3. die GitHub-Milestones und -Issues – offene Aufgaben, was in Arbeit und was erledigt ist
4. diese Datei – Bauen und Testen
5. `docs/internal/offene-fragen.md` – was am Gerät noch unklar ist

Weitergearbeitet wird beim ersten offenen Issue im aktuellen Milestone. Ein Test-Issue ohne grünen Lauf im Dev Container ist zuerst abzunehmen – nicht weiterbauen und das Testen aufschieben.

Das Verzeichnis `_input/` (Wissensstand, Python-Referenzcode, Testbilder) liegt nur lokal vor und ist nicht Teil des Repositorys. Mehrere Anforderungen verweisen darauf.
