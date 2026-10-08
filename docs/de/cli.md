---
title: Kommandozeile
---


Die fünf Unterbefehle von `UnboundAir` und die Optionen, die sie annehmen (DO-19, Anforderungen BE-01 bis BE-05). Diese Datei beschreibt, was ein Befehl **tut** — ob er den Scanner anfasst, was er schreibt, womit er fertig ist. Welche Einstellung welchen Default hat, steht in [`configuration.md`](configuration.md) und nur dort.

Aufgerufen wird entweder das Jar oder der Container; das Kommando ist dasselbe:

```sh
java -jar unboundair.jar status
docker run --network=host ghcr.io/digiwomb-dev/unboundair:nightly status
```

**Ohne Kommando gibt es nur die Hilfe und Exit-Code 1.** Kein Befehl heißt nicht „nimm den Dienst", sondern ist ein Fehler — deshalb steht in beiden Deployment-Beispielen in [`operations.md`](operations.md) ausdrücklich `run`.

## Die Befehle im Überblick

| Befehl | Scanner nötig | Schreibt | Anforderung |
|---|---|---|---|
| `status` | ja | Status und Firmware-Version auf stdout | BE-01 |
| `scan` | ja | die verarbeitete Seite als Datei, mit `--keep-raw` zusätzlich das Rohbild | BE-02 |
| `crop IN OUT` | **nein** | die zugeschnittene Datei | BE-03 |
| `measure` | ja | eine Zusammenfassung auf stdout; die Seiten landen in einem temporären Verzeichnis und werden gelöscht | BE-04 |
| `run` | ja | PDFs in die Outbox, von dort an die Ausgabe-Module | BE-05 |

## `status` — lebt das Gerät?

Fragt den Scanner nach seinem Zustand und seiner Firmware-Version und schreibt beides auf stdout (BE-01):

```
Status: scanready
Firmware: NB0a.032
```

Das ist der erste Befehl bei jedem Verdacht, der Scanner sei nicht erreichbar: Er braucht nichts außer der Netzwerkverbindung, verändert nichts und beantwortet die Frage, die alle anderen Befehle voraussetzen. Antwortet er mit einem Fehler statt mit einem Status, ist das Problem im Netz und nicht in der Konfiguration — weiter in [`troubleshooting.md`](troubleshooting.md).

Status und Firmware werden über **zwei** Verbindungen geholt, nicht über eine: Eine Verbindung pro Vorgang ist die Regel aus SC-02, und das Gerät verträgt kein Bündeln.

## `scan` — eine Seite, von Hand ausgelöst

Scannt genau eine Seite, verarbeitet sie (zuschneiden, dann Farbmodus) und schreibt sie als Datei (BE-02). Danach endet der Befehl — er wartet nicht auf weitere Seiten und baut kein PDF; das ist die Aufgabe von `run`.

```sh
java -jar unboundair.jar scan --dpi 600 --out page.jpg
```

Ohne `--out` entsteht ein Name mit Zeitstempel und Auflösung, etwa `iscan_20260922-143500_300dpi.jpg`. Die Auflösung im Namen ist die **tatsächliche**, nicht die angeforderte: Eine alte Firmware zwingt 600 DPI auf 300 herunter (SC-07, SC-08), und ein Dateiname, der 600 behauptet, wäre eine Lüge. Mit `--keep-raw` kommt das unveränderte Scanner-Bild daneben zu liegen, mit `_raw` vor der Endung (SV-06) — gedacht zum Vergleichen, wenn ein Zuschnitt falsch aussieht.

**In `bw` endet der Standardname auf `.pbm`,** weil die Seite dann ein 1-Bit-Bild ist und keine JPEG-Datei mehr. Ein ausdrücklich genannter `--out`-Name bleibt dagegen so stehen, wie er genannt wurde — die Datei gehört dem Aufrufer, auch wenn der Name nicht zum Inhalt passt. Die Ergebniszeile nennt immer den Pfad, der wirklich geschrieben wurde.

## `crop` — zuschneiden ohne Gerät

Schneidet eine vorhandene JPEG-Datei zu und schreibt das Ergebnis (BE-03):

```sh
java -jar unboundair.jar crop input.jpg output.jpg
```

**Der einzige Befehl, der keinen Scanner braucht.** Damit ist er der Weg, den Zuschnitt an einem Bild auszuprobieren, das schon existiert — etwa an einem mit `--keep-raw` aufgehobenen Rohbild. Beide Argumente sind Pflicht, in dieser Reihenfolge; fehlt eines, bricht der Befehl mit einer Meldung ab.

`crop` ignoriert `--color-mode`, `--bw-threshold` und `--keep-raw` absichtlich. Es ist eine reine Bildoperation und darf die Farbe einer Seite nicht ändern; diese Optionen gehören zum Scannen. Findet der Zuschnitt keinen schwarzen Rand, bleibt die Datei bytegleich erhalten (SV-01) — das ist kein Fehlschlag, sondern der zweite Fall der Anforderung.

## `measure` — das Gerät ausmessen

Fährt den Dienst-Loop für eine begrenzte Zeit und berichtet, was das Gerät dabei getan hat (BE-04):

```sh
java -jar unboundair.jar measure --minutes 10 --poll-seconds 3
```

Die Zusammenfassung nennt die Zahl der Seiten, die Abstände zwischen ihnen (Mittel, kürzester, längster), die Zahl der `devbusy`-Antworten, ob und wann das Gerät offline ging, und auffällig kurze Abstände als möglichen Doppelscan. Jede Zeile trägt die offene Frage, auf die sie zielt — OF-01 bis OF-04 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md).

**`measure` gibt nichts an ein Ausgabe-Modul.** Das ist ausdrücklich so verlangt und nicht ein Nebeneffekt: Ein Messlauf darf keine Probescans in ein echtes Dokumentenarchiv legen. Die Seiten entstehen in einem temporären Verzeichnis, das nach dem Lauf gelöscht wird — sie sind Abfall der Messung, nicht ihr Ergebnis.

Dieser Befehl ist der Grund, warum die vier Zeitwerte in [`configuration.md`](configuration.md) als vorläufig gekennzeichnet sind: Sie sind geschätzt, und `measure` liefert die Zahlen, aus denen die endgültigen werden.

## `run` — der Dienst

Der Dauerbetrieb (BE-05): Der Loop fragt den Scanner regelmäßig nach seinem Status, scannt eine eingelegte Seite von selbst, sammelt Seiten innerhalb des Zeitfensters zu einem Dokument, baut das PDF und legt es in die Outbox. Von dort holt es der Outbox-Läufer ab und übergibt es den aktiven Ausgabe-Modulen.

`run` ist der Befehl, der in einem Container steht. Er nimmt seine Werte deshalb aus den Einstellungen, nicht aus Flags — Abfrageabstand, Zeitfenster, Outbox-Pfad, Module und paperless-Zugang stehen alle in [`configuration.md`](configuration.md). Beim Beenden mit SIGTERM oder SIGINT schließt er den offenen Batch noch ab (DL-07); was das für den Stop-Timeout bedeutet, steht in [`operations.md`](operations.md).

Ein nicht erreichbarer Scanner ist für `run` ein regulärer Zustand, kein Fehler: Der Loop fragt dann im größeren Abstand weiter und findet von selbst zurück (DL-02).

## Die Optionen

Gelten für die Befehle, bei denen sie Sinn haben; die übrigen ignorieren sie.

| Option | Default | Wirkung |
|---|---|---|
| `--host HOST` | `192.168.18.33` | Adresse des Scanners (SC-06). |
| `--port PORT` | `23` | Port des Scanners (SC-06). |
| `--dpi 300\|600` | `300` | Auflösung für `scan` und `run` (SC-07). |
| `--out DATEI` | Name mit Zeitstempel | Zieldatei für `scan`. |
| `--color-mode gray\|color\|bw` | `gray` | Farbmodus für `scan` und `run` (SV-03, SV-08). |
| `--bw-threshold N` | `128` | Helligkeitsschwelle für `bw`, gültig `1..255` (SV-08). |
| `--keep-raw` | aus | Legt bei `scan` und `run` zusätzlich das Rohbild ab (SV-06). |
| `--minutes N` | `10` | Dauer eines `measure`-Laufs. |
| `--poll-seconds N` | `3` | Abfrageabstand während `measure`. |

**Ein Flag gewinnt über die Einstellung, die Einstellung über die Gerätekonstante.** Das gilt für `--host` und `--port` genauso wie für `--dpi`, `--color-mode`, `--bw-threshold` und `--keep-raw`: Wird das Flag nicht genannt, zählt die Einstellung aus [`configuration.md`](configuration.md), und erst wenn auch die fehlt, der eingebaute Default. Diese Reihenfolge ist der Grund, warum `run` in einem Container überhaupt eine andere Adresse erreichen kann als die eingebaute — ein Flag kann dort niemand tippen.

`--minutes` und `--poll-seconds` fallen aus dieser Regel heraus: Sie haben keine Entsprechung unter `unboundair.*`, weil sie einen einzelnen Messlauf steuern und nicht den Betrieb. Dass `--poll-seconds` denselben Vorgabewert wie `unboundair.poll-interval` trägt, ist Absicht — `measure` soll das Verhalten messen, das der Dienst zeigen wird.

Eine unbekannte Option bricht den Aufruf mit einer Meldung ab, statt sie zu überlesen. Das ist ein Unterschied zu den Umgebungsvariablen, bei denen ein Tippfehler stillschweigend auf dem Default bleibt — die Falle aus KL-01, beschrieben in [`configuration.md`](configuration.md).

## Gegen den Fake-Scanner

Jeder Befehl, der den Scanner anfasst, lässt sich gegen den eingebauten Fake richten — genau dafür sind `--host` und `--port` Flags:

```sh
java -jar unboundair.jar status --host 127.0.0.1 --port 2323
```

Wie der Fake gestartet wird und was er beantwortet, steht in [`development.md`](development.md). Diese Datei wiederholt es nicht.
