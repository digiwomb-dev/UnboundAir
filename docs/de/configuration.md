---
title: Konfiguration
---


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

> **Diese vier Werte sind vorläufig.** Sie sind geschätzt, nicht gemessen. Der Befehl `measure` liefert die Zahlen, aus denen die endgültigen Defaults abgeleitet werden; bis dahin bleiben OF-01 bis OF-03 in `docs/internal/offene-fragen.md` offen. Wer den Dienst heute betreibt und ein besseres Verhalten beobachtet, sollte die Werte anpassen – dafür sind sie konfigurierbar.

### Scanner

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.scanner.host` | `UNBOUNDAIR_SCANNER_HOST` | `192.168.18.33` | Adresse des Scanners (SC-06). Im Echtbetrieb eine Konstante des Geräts. |
| `unboundair.scanner.port` | `UNBOUNDAIR_SCANNER_PORT` | `23` | Port des Scanners (SC-06). |

Beide sind einstellbar, damit die Tests gegen den Fake-Scanner auf einem freien Port laufen können, ohne dass dafür Code geändert werden muss.

### Seitenverarbeitung

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.color-mode` | `UNBOUNDAIR_COLORMODE` | `gray` | `gray` wandelt die Seite verlustfrei in Graustufen (`jpegtran -grayscale`), `color` lässt sie farbig (SV-03). `bw` wandelt die Seite in 1-bit-Schwarz-Weiß – das ist im Gegensatz zu den beiden anderen **nicht** verlustfrei: Aus 256 Graustufen wird je Pixel ein einziges Bit, was einmal verworfen ist, lässt sich nicht wiederherstellen (SV-08). |
| `unboundair.bw-threshold` | `UNBOUNDAIR_BWTHRESHOLD` | `128` | Helligkeits-Schwelle für `bw`, gültig `1..255`: Was dunkler als die Schwelle ist, wird schwarz. Ein **niedrigerer** Wert ergibt eine hellere Seite mit weniger zugelaufener Schrift, ein **höherer** eine dunklere, fettere. Gilt nur mit `color-mode = bw` (SV-08). |
| `unboundair.keep-raw` | `UNBOUNDAIR_KEEPRAW` | `false` | Legt zusätzlich das unbearbeitete JPEG ab (SV-06). Zur Fehlersuche gedacht, kostet den doppelten Platz. |
| `unboundair.dpi` | `UNBOUNDAIR_DPI` | `300` | Scan-Auflösung in DPI, 300 oder 600 (SC-07, SC-08). |

`bw` braucht für den PDF-Weg das Programm `jbig2` – ohne es lässt sich keine Schwarz-Weiß-Seite ins PDF übernehmen.

### Ausgabe

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` | *(leer)* | Komma-Liste der aktiven Ausgabe-Module, z. B. `paperless` (AU-03). Leer heißt: kein Modul bekommt Dokumente. |
| `unboundair.outbox.path` | `UNBOUNDAIR_OUTBOX_PATH` | `/var/lib/unboundair/outbox` | Wo fertige Dokumente liegen, bis ein Modul sie angenommen hat (AU-04). Gehört im Container auf ein dauerhaftes Volume, sonst gehen bei einem Neustart nicht zugestellte Dokumente verloren. |
| `unboundair.output.paperless.base-url` | `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL` | *(leer)* | Adresse der paperless-ngx-Instanz, z. B. `https://paperless.example.org` (AU-05). Ohne sinnvollen Standardwert – ohne sie ist das Modul nicht nutzbar. |
| `unboundair.output.paperless.token` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN` | *(leer)* | Das API-Token direkt (AU-05). Entweder das oder die Token-Datei – wie die beiden zusammenspielen, steht unter „Token-Auflösung". |
| `unboundair.output.paperless.token-file` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` | *(leer)* | Pfad zu einer Datei, die das Token enthält, z. B. ein eingebundenes Secret (AU-05). |
| `unboundair.output.paperless.tags` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TAGS` | *(leer)* | Nummern der Schlagwörter, die jedes Dokument bekommt. Leer heißt: keine. |
| `unboundair.output.paperless.correspondent` | `UNBOUNDAIR_OUTPUT_PAPERLESS_CORRESPONDENT` | *(nicht gesetzt)* | Nummer des Absenders. Ohne Wert leitet paperless ihn selbst her. |
| `unboundair.output.paperless.document-type` | `UNBOUNDAIR_OUTPUT_PAPERLESS_DOCUMENTTYPE` | *(nicht gesetzt)* | Nummer des Dokumenttyps. Ohne Wert leitet paperless ihn selbst her. |

#### Token-Auflösung

Das hochgeladene Dokument kennt genau einen Token, die Konfiguration aber zwei Quellen dafür (AU-05): den direkt gesetzten Token und die Token-Datei. Die Auflösung (`PaperlessSettings.fromConfigured`) folgt drei Fällen: Ist eine Token-Datei gesetzt, gewinnt sie – auch dann, wenn zusätzlich ein Token gesetzt ist. Die Datei muss dann lesbar sein und einen nicht-leeren Inhalt haben, sonst startet der Dienst nicht und nennt in der Meldung den Pfad. Ist keine Datei gesetzt, gilt der direkt gesetzte Token. Ist keines von beidem gesetzt, startet der Dienst ebenfalls nicht – mit einer Meldung, die genau das benennt.

Dieses frühe Scheitern ist Absicht: Ein Modul mit leerem Token würde den Fehler erst beim ersten Dokument als undurchsichtiges 401 bemerken, lange nach dem Start. Dass eine gesetzte Datei einen gleichzeitig gesetzten Token still übergeht, folgt derselben Überlegung: Das Ersetzen des Dateiinhalts ist die vorgesehene Art, den Token zu wechseln – ein daneben stehen gebliebener alter Token dürfte diesen Wechsel nicht unsichtbar aushebeln. Ein aus der Datei gelesener Token wird noch von umgebendem Leerraum befreit, damit der fast unvermeidliche Zeilenumbruch am Dateiende (`echo "token" > datei`) nicht den Upload bricht.

## Was hier absichtlich nicht steht

- **Der paperless-Token als Wert.** Secrets gehören nicht in eine Konfigurationsdatei. Der Token kommt als Umgebungsvariable (`unboundair.output.paperless.token`) oder als eingebundene Datei, deren Pfad über eine Umgebungsvariable kommt (`unboundair.output.paperless.token-file`, AU-05). Welche Quelle gilt und warum der Dienst ohne beide gar nicht erst startet, steht unter „Token-Auflösung".
- **Die Backoff-Werte der Outbox.** Nach dem ersten Fehlversuch wartet die Outbox 30 Sekunden, bei jedem weiteren verdoppelt sich die Wartezeit, gedeckelt bei einer Stunde. Das sind bewusst keine Einstellungen, sondern Konstruktor-Parameter von `Outbox`: Sie stimmen einen Algorithmus pro Instanz ab, den niemand im Betrieb umstellen muss – dafür bekäme die Datei nur einen Schalter, den niemand dreht.
- **`normalize` (SV-04).** Zurückgestellt, bis entschieden ist, was es tun soll – siehe „Offene Entscheidungen" in `docs/internal/plan.md`.
- **Die Zeitzone.** Der Dateiname eines Dokuments nutzt die lokale Zeit des Containers. Gesteuert wird sie über die übliche Umgebungsvariable `TZ`, nicht über eine eigene Einstellung.
- **Log-Level.** Logging läuft über die Standardmittel von Spring Boot und Logback (`logging.level.*`), nicht über eigene `unboundair.*`-Einstellungen. Die Ausgabe geht auf stdout (KL-02).
