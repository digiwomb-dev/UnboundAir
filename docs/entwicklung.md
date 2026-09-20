# Entwicklung

Wie man `UnboundAir` baut und testet. Gearbeitet wird ausschließlich im Dev Container – auf dem Rechner selbst muss außer einer Container-Runtime und dem Dev-Container-Tooling nichts installiert sein, insbesondere kein JDK und kein Gradle.

## Voraussetzungen

- Eine Container-Runtime (z. B. Podman oder Docker)
- Dev-Container-Unterstützung: entweder die Erweiterung „Dev Containers" in VS Code oder die `devcontainer`-CLI
- Git

Mehr nicht. JDK, Gradle und `jpegtran` bringt der Container mit.

## Dev Container starten

Repository klonen und im Dev Container öffnen. In VS Code: Ordner öffnen, dann „Reopen in Container". Mit der CLI:

```bash
devcontainer up --workspace-folder .
```

Beim ersten Start wird das Image gebaut, das dauert ein paar Minuten. Danach prüfen, ob die Umgebung stimmt:

```bash
java -version      # erwartet: OpenJDK 26
jpegtran -version  # erwartet: eine libjpeg-turbo-Version
```

Beides muss antworten. `jpegtran` ist keine Kür: Zuschnitt und Graustufen-Umwandlung laufen ausschließlich darüber, ohne das Programm schlagen die entsprechenden Tests fehl.

## Bauen und testen

Alles im Dev Container ausführen:

```bash
./gradlew build   # kompilieren, Linter, Tests
./gradlew test    # nur Tests
./gradlew ktlintCheck   # nur Linter
```

Die Tests brauchen kein Netzwerk und keine echten Geräte. Der Scanner wird durch einen Fake-Scanner ersetzt, der im Test als TCP-Server läuft und das Verhalten des echten Geräts nachbildet – inklusive seiner Eigenheiten wie der Füllbytes in den Antworten.

**Der echte Scanner wird nie für Tests verwendet.** Er ist nur nach ausdrücklicher Freigabe und nur für Messläufe (`measure`) im Spiel.

## Gradle-Wrapper

Der Wrapper ist im Repository eingecheckt, inklusive `gradle-wrapper.jar`. Die Datei stammt aus der offiziellen Gradle-Veröffentlichung und wurde gegen die von Gradle publizierte Prüfsumme verifiziert:

| | Wert |
|---|---|
| Gradle-Version | 9.7.1 |
| SHA-256 des `gradle-wrapper.jar` | `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |
| Quelle der Prüfsumme | `https://services.gradle.org/versions/all`, Feld `wrapperChecksum` |

Beim Anheben der Gradle-Version ist diese Prüfsumme mitzuführen und erneut abzugleichen. Nachprüfen lässt sie sich jederzeit:

```bash
sha256sum gradle/wrapper/gradle-wrapper.jar
```

## Zusammenarbeit am Repository

Gearbeitet wird derzeit direkt auf `main`, ohne Branches und Pull Requests – der Stand muss nach jedem abgeschlossenen Schritt sofort abholbar sein, weil Tests auf einem anderen Rechner von Hand ausgeführt werden. Commits folgen trotzdem den Conventional Commits und bleiben klein.

## Warum der Umweg über den Dev Container

Der Container enthält dieselben Systemabhängigkeiten wie das spätere Laufzeit-Image: dieselbe JDK-Hauptversion, dasselbe `jpegtran`. Driften die beiden auseinander, laufen die Tests grün und der Dienst fällt im Betrieb um. Deshalb gilt: gebaut und getestet wird im Container, nicht daneben.

## Teststand

Dieser Abschnitt hält fest, welcher Stand zuletzt tatsächlich ausgeführt wurde und was als Nächstes zu prüfen ist.

**Hintergrund:** Die Entwicklungsumgebung, in der der Code entsteht, hat keine Container-Runtime. Tests werden deshalb von Hand auf einem Rechner mit Runtime ausgeführt und die Ergebnisse zurückgemeldet. Solange das so ist, gilt jede Aufgabe erst dann als abgenommen, wenn ihr Ergebnis hier steht.

| Was | Stand |
|---|---|
| Zuletzt getesteter Commit | *(noch keiner)* |
| Ergebnis | *(ausstehend)* |
| Als Nächstes zu prüfen | Testpunkt 1 – Dev Container startet, `java -version` und `jpegtran -version` antworten |

### Testpunkte in Meilenstein 1

Meilenstein 1 ist in vier Testpunkte geschnitten, damit ein Fehlschlag klein und zuordenbar bleibt.

1. **Dev Container** – Image baut, `java -version` meldet 26, `jpegtran -version` antwortet. Deckt DC-01 und DC-02 ab.
2. **Gradle-Gerüst** – `./gradlew build` läuft durch. Deckt die Aufgaben T1.5 bis T1.9 ab.
3. **Scanner-Client** – `./gradlew test` grün, Fake-Scanner und Protokoll-Tests. Deckt T1.10 bis T1.14 ab (SC-01 bis SC-07, TE-01).
4. **Befehle** – `./gradlew test` grün, `status` und `scan` gegen den Fake-Scanner. Deckt T1.15 bis T1.17 ab (BE-01, BE-02 teilweise, DC-03).
