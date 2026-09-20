# Entwicklung

Wie man `UnboundAir` baut und testet. Gearbeitet wird ausschließlich im Dev Container – auf dem Rechner selbst muss außer einer Container-Runtime und dem Dev-Container-Tooling nichts installiert sein, insbesondere kein JDK und kein Gradle.

> **Stand: Meilenstein 1, im Aufbau.** Das Gradle-Projekt existiert noch nicht. Abschnitte, die es voraussetzen, sind als „noch nicht vorhanden" gekennzeichnet. Was heute schon geht, steht unter „Dev Container starten".

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

Beim ersten Start wird das Image gebaut, das dauert ein paar Minuten. Danach prüfen, ob die Umgebung stimmt:

```bash
java -version      # erwartet: Temurin, Version 26
jpegtran -version  # erwartet: eine libjpeg-turbo-Version
```

Beides muss antworten. `jpegtran` ist keine Kür: Zuschnitt und Graustufen-Umwandlung laufen ausschließlich darüber, ohne das Programm schlagen die entsprechenden Tests fehl.

## Bauen und testen

> **Noch nicht vorhanden.** Das Gradle-Projekt entsteht in den Aufgaben T1.5 bis T1.9, siehe `meilenstein-1.md`. Die folgenden Befehle funktionieren erst danach.

Alles im Dev Container ausführen:

```bash
./gradlew build         # kompilieren, Linter, Tests
./gradlew test          # nur Tests
./gradlew ktlintCheck   # nur Linter
```

Die Tests kommen ohne echte Geräte und ohne fremde Dienste aus: Der Scanner wird durch einen Fake-Scanner ersetzt, der im Test als TCP-Server läuft und das Verhalten des echten Geräts nachbildet – inklusive seiner Eigenheiten wie der Füllbytes in den Antworten.

Eine Netzwerkverbindung braucht trotzdem, wer zum ersten Mal baut: Der Wrapper lädt die Gradle-Distribution, Gradle lädt die Abhängigkeiten. Beides landet im Cache und wird danach nicht mehr benötigt.

**Der echte Scanner wird nie für Tests verwendet.** Er ist nur nach ausdrücklicher Freigabe und nur für Messläufe (`measure`) im Spiel.

## Gradle-Wrapper

> **Noch nicht vorhanden.** Der Wrapper wird in T1.8 eingecheckt.

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

Gearbeitet wird derzeit direkt auf `main`, ohne Branches und Pull Requests – der Stand muss nach jedem abgeschlossenen Schritt sofort abholbar sein, weil Tests auf einem anderen Rechner von Hand ausgeführt werden. Commits folgen trotzdem den Conventional Commits und bleiben klein.

## Warum der Umweg über den Dev Container

Der Container enthält dieselben Systemabhängigkeiten wie das spätere Laufzeit-Image: dieselbe JDK-Hauptversion, dasselbe `jpegtran`. Driften die beiden auseinander, laufen die Tests grün und der Dienst fällt im Betrieb um. Deshalb gilt: gebaut und getestet wird im Container, nicht daneben.

## Wie getestet wird, solange keine Runtime da ist

Die Umgebung, in der der Code entsteht, hat keine Container-Runtime und bekommt auch keine. Das blockiert nichts, verschiebt aber die Ausführung: Gebaut und committet wird dort, ausgeführt auf einem Rechner mit Runtime.

Der Ablauf je Schritt:

1. Ein abgeschlossenes Stück wird gebaut, committet und nach `origin/main` gepusht.
2. Es folgt eine Ansage, welche Befehle auszuführen sind und was dabei herauskommen soll.
3. Die Ausgabe wird zurückgemeldet – auch im Fehlerfall.
4. Das Ergebnis wird im Fortschritt des jeweiligen Meilensteins festgehalten, erst dann geht es weiter.

Eine Aufgabe gilt erst als abgenommen, wenn ihr Testergebnis dort steht. Aufgaben, die geschrieben, aber noch nicht ausgeführt wurden, werden ausdrücklich als „nicht verifiziert" geführt.

Den aktuellen Teststand und die Testpunkte des laufenden Meilensteins findest du in `meilenstein-1.md`.
