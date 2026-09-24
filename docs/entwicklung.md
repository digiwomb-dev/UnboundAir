# Entwicklung

Wie man `UnboundAir` baut und testet. Gearbeitet wird ausschließlich im Dev Container – auf dem Rechner selbst muss außer einer Container-Runtime und dem Dev-Container-Tooling nichts installiert sein, insbesondere kein JDK und kein Gradle.

> **Stand: Meilenstein 2, in Arbeit.** Aus Meilenstein 1 stehen Gradle-Projekt, Scanner-Client, Fake-Scanner und die Befehle `status` und `scan` (getestet gegen den Fake-Scanner); Meilenstein 2 ergänzt Zuschnitt und Graustufen. Den aktuellen Stand zeigen die [GitHub-Issues](https://github.com/digiwomb-dev/UnboundAir/issues) im [Milestone 2](https://github.com/digiwomb-dev/UnboundAir/milestones/2).

## Voraussetzungen

- Eine Container-Runtime (z. B. Podman oder Docker)
- Dev-Container-Unterstützung: entweder die Erweiterung „Dev Containers" in VS Code oder die `devcontainer`-CLI
- Git

Mehr nicht. Das JDK und `jpegtran` bringt der Container mit; Gradle lädt der Wrapper beim ersten Lauf selbst herunter.

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

Die `devcontainer`-CLI kann das gemeinsame Git-Verzeichnis mitmounten (`--mount-git-worktree-common-dir`), verlangt dafür aber mit relativen Pfaden angelegte Worktrees (`git worktree add --relative-paths`, ab Git 2.48). Siehe OF-12 in `offene-fragen.md`.

Beim ersten Start wird das Image gebaut, das dauert einige Minuten. Die Ausgabe wirkt dabei streckenweise wie eingefroren, weil die Fortschrittsanzeige der Container-Runtime gepuffert durchgereicht wird. `--log-level debug` zeigt stattdessen jeden Schritt einzeln.

Danach prüfen, ob die Umgebung stimmt:

```bash
java -version      # erwartet: Temurin, Version 26
jpegtran -version  # erwartet: eine libjpeg-turbo-Version
```

Beides muss antworten. `jpegtran` ist keine Kür: Zuschnitt und Graustufen-Umwandlung laufen ausschließlich darüber, ohne das Programm schlagen die entsprechenden Tests fehl.

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

Geprüft wird mit **ktlint**; Spotless ist nur der Rahmen, der es startet. Warum dieser Umweg nötig ist, steht in `plan.md` unter „Entschieden – nicht mehr offen" und in `offene-fragen.md` unter OF-11.

Die Tests kommen ohne echte Geräte und ohne fremde Dienste aus: Der Scanner wird durch einen Fake-Scanner ersetzt, der im Test als TCP-Server läuft und das Verhalten des echten Geräts nachbildet – inklusive seiner Eigenheiten wie der Füllbytes in den Antworten.

Eine Netzwerkverbindung braucht trotzdem, wer zum ersten Mal baut: Der Wrapper lädt die Gradle-Distribution, Gradle lädt die Abhängigkeiten. Beides landet im Cache und wird danach nicht mehr benötigt.

**Der echte Scanner wird nie für Tests verwendet.** Er ist nur nach ausdrücklicher Freigabe und nur für Messläufe (`measure`) im Spiel.

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

## Zusammenarbeit am Repository

Arbeit wird über GitHub-Issues organisiert: je Aufgabe ein Issue, je Issue ein Branch (`gh issue develop`), Commits referenzieren das Issue (`(#n)`/`Closes #n`), der Abschluss läuft über einen Pull Request. Commits folgen den Conventional Commits und bleiben klein.

## Warum der Umweg über den Dev Container

Der Container enthält dieselben Systemabhängigkeiten wie das spätere Laufzeit-Image: dieselbe JDK-Hauptversion, dasselbe `jpegtran`. Driften die beiden auseinander, laufen die Tests grün und der Dienst fällt im Betrieb um. Deshalb gilt: gebaut und getestet wird im Container, nicht daneben.

### Bekannte Eigenheiten

- Die `devcontainer`-CLI ruft fest `docker` auf. Mit Podman muss `--docker-path podman` mitgegeben werden, sonst bricht sie mit `spawn docker ENOENT` ab.
- Die Ausgabe langer Läufe wirkt eingefroren, weil Fortschrittsanzeigen gepuffert durchgereicht werden. `--log-level debug` zeigt die einzelnen Schritte.
- Der Digest-Pin des Basis-Image erzeugt eine Warnung der CLI („Could not parse image name"). Folgenlos, siehe oben.
- Läuft der Dev Container in einer Umgebung, die selbst nur eine Benutzerkennung kennt (verschachtelte Container ohne eigene UID-Bereiche), schlägt jedes Ändern von Dateibesitz fehl. Das `Dockerfile` ist darauf eingestellt: Der Download-Sandkasten von `apt` läuft als `root`, und das `chown` auf das Gradle-Verzeichnis darf fehlschlagen – nötig ist es dort ohnehin nicht, weil alle Dateien derselben Kennung gehören.

## Einstieg für eine neue Arbeitssitzung

Wer hier neu dazukommt, liest in dieser Reihenfolge:

1. `AGENTS.md` – wie gearbeitet wird, Leitplanken, Regeln
2. `docs/plan.md` – Auftrag, feste Entscheidungen, Anforderungen mit IDs
3. die GitHub-Milestones und -Issues – offene Aufgaben, was in Arbeit und was erledigt ist
4. diese Datei – Bauen und Testen
5. `docs/offene-fragen.md` – was am Gerät noch unklar ist

Weitergearbeitet wird beim ersten offenen Issue im aktuellen Milestone. Ein Test-Issue ohne grünen Lauf im Dev Container ist zuerst abzunehmen – nicht weiterbauen und das Testen aufschieben.

Das Verzeichnis `_input/` (Wissensstand, Python-Referenzcode, Testbilder) liegt nur lokal vor und ist nicht Teil des Repositorys. Mehrere Anforderungen verweisen darauf.
