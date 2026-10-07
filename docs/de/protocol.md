---
title: Scanner-Protokoll
---


Fachliche Grundlage für den Scanner-Client (SC-01 bis SC-08). Diese Datei beschreibt, **wie mit dem Gerät gesprochen wird**: Verbindung, Befehle, Antworten, Scan-Ablauf und Zeitverhalten. **[Gerät]**

Das Gerät selbst — WLAN, Einzug, Messwerte, Eigenheiten — gehört nach `hardware.md` (DO-02). **[Gerät]** Was noch ungeklärt ist, gehört nach `docs/internal/offene-fragen.md` und wird hier nur verwiesen, nicht wiederholt. **[Gerät]**

## Herkunft der Aussagen

Jede Aussage trägt ihre Herkunftsmarkierung. **[Gerät]**

- **[Gerät]** — am echten Gerät verifiziert **[Gerät]**
- **[App/s400w]** — aus der Windows-App bzw. der s400w-Implementierung, am Gerät noch nicht getestet **[App/s400w]**
- **[Handbuch]** — aus dem Handbuch **[Handbuch]**
- **[Scan]** — an 1–2 echten Scans beobachtet **[Scan]**
- **[offen]** — noch unbekannt **[offen]**

Es wird nichts erfunden: Wo die Quelle `[offen]` sagt, sagt diese Datei `[offen]`. **[offen]**

## Verbindung

Der Scanner hört auf `192.168.18.33`, Port `23`, über TCP, ohne Authentifizierung und ohne Verschlüsselung. **[Gerät]**

Es wird eine TCP-Verbindung pro Vorgang geöffnet, nicht pro Befehl. **[App/s400w]** Ein Vorgang ist entweder eine einzelne Statusabfrage oder ein kompletter Scan (`status` → DPI → `scan` → `jpegsize` → `jpegdata`). **[App/s400w]** Der Scan schickt innerhalb seiner Verbindung erneut `status`; das eröffnet keine neue Verbindung. **[App/s400w]**

## Befehle

Jeder Befehl ist 4 Byte lang, in Sende-Reihenfolge; in der Windows-App als Int32 little endian abgelegt. **[Gerät]** Das gilt verifiziert für `status`, `version`, `dpi300`, `scan`, `jpegsize` und `jpegdata`; für die übrigen Befehle stammt die Angabe aus App bzw. s400w. **[App/s400w]**

| Befehl | Bytes (Sende-Reihenfolge) | Antwort (Präfix) | Herkunft |
|---|---|---|---|
| `status` | `00 60 00 50` | `scanready` / `nopaper` **[Gerät]** / `devbusy` / `battlow` **[App/s400w]** | **[Gerät]** **[App/s400w]** |
| `version` | `30 30 20 20` | z. B. `NB0a.032` **[Gerät]** | **[Gerät]** |
| `dpi300` | `40 30 20 10` | `dpistd` **[Gerät]** | **[Gerät]** |
| `dpi600` | `80 70 60 50` | `dpifine` **[App/s400w]** | **[App/s400w]** |
| `scan` (Start) | `00 20 00 10` | `scango` **[Gerät]** | **[Gerät]** |
| `jpegsize` | `00 D0 00 C0` | `jpegsize` + uint32 LE (insgesamt 12 Byte) **[Gerät]** | **[Gerät]** |
| `jpegdata` | `00 F0 00 E0` | exakt `size` Byte JPEG **[Gerät]** | **[Gerät]** |
| `preview` | `40 40 30 30` | Rohzeilen bis `previewend` (für diesen Dienst unnötig) **[App/s400w]** | **[App/s400w]** |
| `clean` | `80 80 70 70` | `cleango` / `cleanend` **[App/s400w]** | **[App/s400w]** |
| `calibrate` | `00 B0 00 A0` | `calgo` / `calibrate` **[App/s400w]** | **[App/s400w]** |

Herkunft je Zeile: `status`-Antworten `scanready` und `nopaper` sind am Gerät verifiziert. **[Gerät]** Die Antworten `devbusy` und `battlow` sind aus App bzw. s400w bekannt, am Gerät nicht getestet. **[App/s400w]** Die `version`-Antwort (z. B. `NB0a.032`) wurde am Gerät beobachtet. **[Gerät]** Die Befehle `dpi300`, `scan`, `jpegsize` und `jpegdata` mit ihren Antworten sind am Gerät verifiziert. **[Gerät]** Die Befehle `dpi600`, `preview`, `clean` und `calibrate` mit ihren Antworten stammen aus App bzw. s400w und sind am Gerät nicht getestet. **[App/s400w]**

Das Gerät kennt keinen Duplex-Befehl: Der komplette Befehlssatz enthält keinen Befehl für beidseitiges Scannen. **[App/s400w]** Die Rückseite wird gescannt, indem das Blatt umgedreht und erneut eingelegt wird. **[App/s400w]**

## Antworten und Füllbytes

Status- und Bestätigungsantworten sind 11 Byte lang: ein Wort, aufgefüllt mit `\x00`-Bytes, abgeschlossen mit `H`. **[Gerät]** Beispiele sind `nopaper\x00\x00\x00H`, `scanready\x00H`, `dpistd\x00\x00\x00\x00H` und `scango\x00\x00\x00\x00H`. **[Gerät]**

Antworten werden deshalb immer per Präfix verglichen; feste Länge oder bestimmtes Padding werden nicht vorausgesetzt. **[Gerät]** So vergleichen auch die Windows-App und s400w. **[App/s400w]**

Die `version`-Antwort folgt diesem 11-Byte-Schema nicht: Die Referenz sendet `NB0a.032\x00`, also 9 Byte ohne abschließendes `H`. **[Gerät]** Ob das echte Gerät es genauso sendet oder ob die Referenz an dieser Stelle ungenau ist, ist ungeklärt — siehe OF-07 in `docs/internal/offene-fragen.md`. **[offen]** Der Präfix-Vergleich setzt deshalb keine feste Länge und kein bestimmtes Padding voraus (SC-03). **[App/s400w]**

Die `jpegsize`-Antwort ist 12 Byte lang (`jpegsize`-Präfix plus uint32 little endian) und wird bei Bedarf über mehrere Lesevorgänge gelesen, bis alle 12 Byte vorliegen. **[Gerät]**

Die `jpegdata`-Antwort liefert exakt `size` Byte JPEG-Nutzlast, wobei `size` die zuvor gelesene Größe ist. **[Gerät]**

## Scan-Ablauf

Der Ablauf entspricht dem s400w-Befehl `scan` ohne Vorschau; so nutzt ihn auch AirScan. **[Gerät]** Die Schritte in festen Reihenfolge: **[Gerät]**

1. `status` senden — die Antwort muss `scanready` sein. **[Gerät]**
2. `dpi300` bzw. `dpi600` senden — die Antwort ist `dpistd` bzw. `dpifine`. **[Gerät]**
3. `scan` senden — die Antwort ist `scango`. **[Gerät]**
4. `jpegsize` senden — Timeout 60 s, weil in dieser Zeit das Blatt eingezogen wird; bei Bedarf mehrere Lesevorgänge, bis 12 Byte vorliegen. **[Gerät]**
5. `jpegdata` senden — exakt `size` Byte lesen, Timeout 30 s pro Lesevorgang. **[Gerät]**

## Pausen und Timeouts

s400w wartet 200 ms vor und nach jedem Senden. **[App/s400w]** Die Timeouts sind: normal 10 s, Bilddaten 30 s pro Lesevorgang, `jpegsize` 60 s. **[App/s400w]**

Der Referenzcode weicht an zwei Stellen von der 200-ms-Regel ab und wartet 500 ms: nach dem Senden von `jpegdata` und nach dem Senden von `status` in der reinen Statusabfrage. **[App/s400w]** Ob diese längere Pause nötig ist oder nur Vorsicht, ist ungeklärt — siehe OF-08 in `docs/internal/offene-fragen.md`. **[offen]** Die längere Wartezeit vor dem Lesen der Massendaten wird ohne Messung nicht gekürzt. **[App/s400w]**

## Firmware-Check vor DPI-Umschaltung

s400w stellt die DPI nur um, wenn die Zahl nach dem Punkt der Versionsangabe ≥ 26 ist. **[App/s400w]** Das Testgerät meldet `NB0a.032`, also 32, und ist damit umschaltbar. **[Gerät]** Die Versionsangabe selbst wurde am Gerät beobachtet, die Regel stammt aus App bzw. s400w. **[Gerät]** **[App/s400w]**

Bei älterer Firmware bleibt der Scanner bei 300 dpi; der Aufrufer wird gewarnt und erfährt die tatsächlich verwendete Auflösung (SC-07, SC-08). **[App/s400w]**

## Polling

Die Mustek-App fragt den Status nicht periodisch per TCP ab, sondern nur bei Aktionen. **[App/s400w]** AirScan fragt ca. alle 8 s ab. **[App/s400w]** Wie sich häufigeres Polling am Gerät auswirkt, ist unbekannt. **[offen]** Offene Punkte dazu — ob Polling das Auto-Off verhindert (OF-01), ob 3-s-Takt `devbusy` auslöst (OF-02) — stehen in `docs/internal/offene-fragen.md` und werden mit `measure` geklärt. **[offen]**

## Abgrenzung

Bilddaten (JPEG-Format, Maße, Hintergrund, Dateigröße) und Zuschnitt gehören nicht hierher. **[Scan]** Sie wurden an echten Scans beobachtet und werden mit den Testbildern (TE-02) an anderer Stelle festgehalten. **[Scan]** Geräte-, WLAN- und Messwerte gehören nach `hardware.md` (DO-02). **[Handbuch]** Die Upload-Schnittstelle von paperless-ngx gehört zum Ausgabe-Modul, nicht zum Scanner-Protokoll. **[App/s400w]**
