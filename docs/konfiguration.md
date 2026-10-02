# Konfiguration

Alle Einstellungen von `UnboundAir` mit Default und Umgebungsvariable (DO-09, Anforderung KL-01). Diese Datei ist die Referenz: Was der Code kennt, steht hier – und was hier steht, gibt es auch im Code.

Die Defaults leben an genau einer Stelle im Code, in `UnboundAirProperties.kt`. Ändert sich dort ein Wert, ist diese Tabelle mitzuziehen; der Slice-Test `ConfigBindingSliceTest` prüft jeden Default gegen den Code und wird rot, wenn jemand nur eine der beiden Seiten anfasst.

## Wie Einstellungen gesetzt werden

Es gibt zwei Schreibweisen für dieselbe Einstellung:

- **Property** – klein, mit Punkten und Bindestrichen: `unboundair.poll-interval`. So steht es in einer `application.yml` oder als `--unboundair.poll-interval=5` auf der Kommandozeile.
- **Umgebungsvariable** – groß, mit Unterstrichen: `UNBOUNDAIR_POLLINTERVAL`. Das ist der übliche Weg im Container.

**Die Umrechnung hat eine Stolperstelle.** Aus jedem **Punkt** wird ein **Unterstrich**, jeder **Bindestrich entfällt ersatzlos**:

| Property | Umgebungsvariable |
|---|---|
| `unboundair.poll-interval` | `UNBOUNDAIR_POLLINTERVAL` |
| `unboundair.offline-poll-interval` | `UNBOUNDAIR_OFFLINEPOLLINTERVAL` |
| `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` |
| `unboundair.outbox.path` | `UNBOUNDAIR_OUTBOX_PATH` |

Der naheliegende Fehler ist, aus dem Bindestrich auch einen Unterstrich zu machen: `UNBOUNDAIR_POLL_INTERVAL` wird von Spring **nicht** erkannt, und zwar ohne Fehlermeldung – die Einstellung bleibt einfach auf ihrem Default. Der Property-Test `PropertyNameMappingPropertyTest` hält diese Regel fest.

## Zeitangaben

Die Zeit-Einstellungen nehmen eine nackte Zahl als **Sekunden** (`20` heißt 20 Sekunden) oder eine Angabe mit Einheit (`500ms`, `2m`, `1h`).

## Die Einstellungen

### Dienst-Loop

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.poll-interval` | `UNBOUNDAIR_POLLINTERVAL` | `3` (Sekunden) | Wie oft der Dienst den Scanner nach seinem Status fragt (DL-01). Jede Abfrage öffnet eine eigene Verbindung. |
| `unboundair.offline-poll-interval` | `UNBOUNDAIR_OFFLINEPOLLINTERVAL` | `10` (Sekunden) | Abfrage-Abstand, solange der Scanner nicht erreichbar ist (DL-02). Ein ausgeschaltetes Gerät im 3-Sekunden-Takt anzusprechen bringt nichts. |
| `unboundair.batch-timeout` | `UNBOUNDAIR_BATCHTIMEOUT` | `20` (Sekunden) | Wie lange nach der letzten Seite auf eine weitere gewartet wird, bevor das Dokument geschlossen wird (DL-04). |
| `unboundair.idle-minutes` | `UNBOUNDAIR_IDLEMINUTES` | *(nicht gesetzt)* | Nach wie vielen Minuten ohne Seite langsamer abgefragt wird (DL-06). Ohne Wert ändert sich nichts – das ist der Auslieferungszustand. |

> **Diese vier Werte sind vorläufig.** Sie sind geschätzt, nicht gemessen. Der Befehl `measure` liefert die Zahlen, aus denen die endgültigen Defaults abgeleitet werden; bis dahin bleiben OF-01 bis OF-03 in `offene-fragen.md` offen. Wer den Dienst heute betreibt und ein besseres Verhalten beobachtet, sollte die Werte anpassen – dafür sind sie konfigurierbar.

### Scanner

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.scanner.host` | `UNBOUNDAIR_SCANNER_HOST` | `192.168.18.33` | Adresse des Scanners (SC-06). Im Echtbetrieb eine Konstante des Geräts. |
| `unboundair.scanner.port` | `UNBOUNDAIR_SCANNER_PORT` | `23` | Port des Scanners (SC-06). |

Beide sind einstellbar, damit die Tests gegen den Fake-Scanner auf einem freien Port laufen können, ohne dass dafür Code geändert werden muss.

### Seitenverarbeitung

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.color-mode` | `UNBOUNDAIR_COLORMODE` | `gray` | `gray` wandelt die Seite verlustfrei in Graustufen (`jpegtran -grayscale`), `color` lässt sie farbig (SV-03). |
| `unboundair.keep-raw` | `UNBOUNDAIR_KEEPRAW` | `false` | Legt zusätzlich das unbearbeitete JPEG ab (SV-06). Zur Fehlersuche gedacht, kostet den doppelten Platz. |

### Ausgabe

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` | *(leer)* | Komma-Liste der aktiven Ausgabe-Module, z. B. `paperless` (AU-03). Leer heißt: kein Modul bekommt Dokumente. |
| `unboundair.outbox.path` | `UNBOUNDAIR_OUTBOX_PATH` | `/var/lib/unboundair/outbox` | Wo fertige Dokumente liegen, bis ein Modul sie angenommen hat (AU-04). Gehört im Container auf ein dauerhaftes Volume, sonst gehen bei einem Neustart nicht zugestellte Dokumente verloren. |

> **Stand Meilenstein 3:** Die Ausgabe-Module und die Outbox sind noch nicht gebaut (Meilenstein 4). Die beiden Einstellungen existieren bereits, werden aber noch nicht ausgewertet. Sie stehen hier, weil sie zur zentralen Konfiguration gehören und der Slice-Test sie prüft – nicht, weil sie schon etwas bewirken.

## Was hier absichtlich nicht steht

- **Der paperless-Token.** Secrets gehören nicht in eine Konfigurationsdatei. Er kommt als Umgebungsvariable oder als eingebundene Datei, deren Pfad über eine Umgebungsvariable kommt (AU-05). Das wird mit dem paperless-Modul in Meilenstein 4 beschrieben.
- **`normalize` (SV-04).** Zurückgestellt, bis entschieden ist, was es tun soll – siehe „Offene Entscheidungen" in `plan.md`.
- **Die Zeitzone.** Der Dateiname eines Dokuments nutzt die lokale Zeit des Containers. Gesteuert wird sie über die übliche Umgebungsvariable `TZ`, nicht über eine eigene Einstellung.
- **Log-Level.** Logging läuft über die Standardmittel von Spring Boot und Logback (`logging.level.*`), nicht über eigene `unboundair.*`-Einstellungen. Die Ausgabe geht auf stdout (KL-02).
