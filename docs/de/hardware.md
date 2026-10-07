---
title: Hardware
---


Das Gerät selbst: was der Scanner körperlich kann, welche Werte daran gemessen wurden und welche Eigenheiten er zeigt. Das Gegenstück ist `docs/de/protocol.md`: Dort steht, **wie** mit dem Gerät gesprochen wird (Befehle, Ablauf, Antwortformate); hier steht, **womit** gesprochen wird. Wo beides sich berührt, wird verwiesen statt wiederholt.

Grundlage ist der Stand aus `_input/iscan-air-wissen.md` (liegt nur lokal vor, wird nie committet), in eigenen Worten hierher übertragen.

## Herkunft der Aussagen

Jede Aussage trägt ihre Herkunft. Die Markierungen sind dieselben wie in `protocol.md`:

**[Gerät]** am echten Gerät verifiziert · **[App/s400w]** aus der Windows-App bzw. der s400w-Implementierung, am Gerät noch nicht getestet · **[Handbuch]** aus dem Handbuch zum S400W · **[Scan]** an 1–2 echten Scans beobachtet · **[offen]** noch unbekannt, wartet auf Messung.

Ohne diese Markierungen wäre nicht erkennbar, welche Werte gemessen und welche nur aus dem Handbuch übernommen sind — deshalb stehen sie bei jeder Aussage, nicht nur bei strittigen.

## Das Gerät im Überblick

- Einzelblatt-Einzug: Jedes Blatt wird einzeln von Hand eingelegt **[Handbuch]**.
- WLAN-only: Der Betrieb läuft über WLAN; die Mini-USB-Buchse dient nur zum Laden, nicht zur Datenübertragung **[Handbuch]**.
- Ausgabeformat ausschließlich JPEG **[Handbuch]**.
- Zwei Auflösungen: 300 und 600 dpi **[Handbuch]**.
- **Kein Duplex:** Der vollständige Befehlssatz enthält keinen Befehl für die Rückseite **[App/s400w]**. Wer beide Seiten will, dreht das Blatt um und legt es erneut ein.

## WLAN und Verbindung

- SSID `DIRECT-xxxxxx_iScanAir`, wobei `xxxxxx` ein gerätespezifischer 6-stelliger Code ist; WPA-Passwort `12345678` **[Handbuch]**.
- Der Scanner vergibt per DHCP Adressen aus `192.168.18.0/24` **[Gerät]** (am Testgerät erhielt der Client `192.168.18.1` **[Gerät]**).
- Die Windows-App prüft genau das: Die SSID beginnt mit `DIRECT-`, die eigene IP beginnt mit `192.168.18` **[App/s400w]**.
- Bis zu 8 Clients gleichzeitig, Reichweite ca. 18–30 m **[Handbuch]**.
- Adresse und Port des Scanners selbst (Default `192.168.18.33`, Port `23`) sowie das Verbindungsverhalten gehören zum Protokoll — siehe `protocol.md`.

## Einschalten und Energie

- Einschalten: POWER-Taste ca. 3 s halten, danach ca. 20 s warten, bis die LED blau blinkt **[Handbuch]**.
- LED dauerhaft blau bedeutet: Ein Client ist verbunden **[Handbuch]**.
- Auto-Off: Ohne Aktion schaltet sich das Gerät nach 5 Minuten ab **[Handbuch]**. Ob eine laufende Statusabfrage als „Aktion" zählt und das Abschalten verhindert, ist **nicht** gemessen **[offen]** — siehe OF-01 in `docs/internal/offene-fragen.md`.
- Verhalten bei niedrigem Akkustand: Die Antwort `battlow` ist aus der App bekannt, wurde am Gerät aber nie ausgelöst **[App/s400w]**. Ob das Gerät dann noch scannt, ob es die Meldung einmal oder dauerhaft sendet und ob ein laufender Scan abbricht, ist **nicht** gemessen **[offen]** — siehe OF-10 in `docs/internal/offene-fragen.md`.

## Scaneigenschaften und Bilddaten

An echten Scans beobachtet (jeweils 1–2 Vorlagen, keine Statistik):

- JPEG mit 300 dpi, enthalten in JFIF- und EXIF-Headern, Chroma-Subsampling 4:2:2 (iMCU 16×8), Quantisierungstabellen entsprechend dem IJG-Standard für Qualität 50 **[Scan]**.
- Die Bildbreite hängt von der Vorlage ab: A4 ergibt ca. 2464 px (ca. 208,6 mm bei 300 dpi), ein schmales Papier ca. 1776 px (ca. 150,4 mm); das Papier liegt dabei rechts an **[Scan]**.
- Der Hintergrund ist fast schwarz (Luma ca. 2–6), das Papier liegt bei ca. 210–220; am Ende folgt ein schwarzer Nachlauf von ca. 12–18 mm, teilweise auch ca. 4 mm am Anfang **[Scan]**.
- Dateigröße als Anhaltspunkt: Ein A4-Brief mit 300 dpi liegt bei ca. 0,9 MB **[Gerät]**.
- Nach dem Zuschnitt gemessen: A4 ca. 206,9 × 291,3 mm, DL-Kuvert ca. 103,0 × 211,2 mm **[Scan]**. Beide Werte weichen vom Papierformat ab — ob der Scanner abschneidet, der Einzug staucht oder die DPI-Angabe nicht der Optik entspricht, ist **nicht** geklärt **[offen]** — siehe OF-05 in `docs/internal/offene-fragen.md`.
- Zu 600 dpi gibt es keine Messwerte: Dauer, Dateigröße, Header-Eintrag und mögliches abweichendes Subsampling sind **nicht** gemessen **[offen]** — siehe OF-06 in `docs/internal/offene-fragen.md`.

## Bekannte Eigenheiten des Geräts

Verhalten, das vom Gerät kommt und kein Protokollfehler ist:

- **Füllbytes in Antworten:** Das Gerät hängt an kurze Antwortwörter Null-Bytes und ein `H` an. Deshalb vergleicht der Client Antworten per Präfix — Details und Byte-Beispiele stehen in `protocol.md`, nicht hier.
- **Seitengrößen weichen vom Papierformat ab** (siehe oben, OF-05) **[Scan]**.
- **`devbusy`:** Das Gerät kann auf eine Statusabfrage mit `devbusy` antworten; die Antwort ist aus der App bekannt **[App/s400w]**, aber wie häufig sie bei regelmäßigem Abfragen kommt, ist **nicht** gemessen **[offen]** — siehe OF-02 in `docs/internal/offene-fragen.md`.
- **Niedriger Akku** (`battlow`, siehe oben, OF-10) **[App/s400w]**.

Protokollfragen, die eng danebenliegen (Antwortlängen, Pausen, Doppelscans), gehören nach `protocol.md` bzw. OF-04, OF-07 und OF-08 in `docs/internal/offene-fragen.md` und werden hier nicht geführt.

## Noch zu messen: Lücken für `measure`

Der Befehl `measure` (BE-04) misst genau diese Lücken am echten Gerät. Bis zur systematischen Messung bleiben die folgenden Felder leer; erfundene Zahlen stehen hier bewusst nicht. Je Eintrag ist vermerkt, welche Zusammenfassungszeile von `measure` ihn füllt und wo die Frage geführt wird.

| Größe | Stand heute | Füllt später | Frage |
|---|---|---|---|
| Hält Status-Polling den Scanner wach? | unbekannt **[offen]** (Auto-Off 5 min **[Handbuch]**) | `went offline: … after … of quiet` | OF-01 |
| `devbusy`-Häufigkeit bei 3-s-Takt | unbekannt **[offen]** (Antwort bekannt **[App/s400w]**) | `devbusy answers: …` | OF-02 |
| Scan-Dauer pro Seite und Abstand zwischen zwei Seiten | unbekannt **[offen]** (nur Dateigröße ca. 0,9 MB **[Gerät]**) | `gaps between pages` (Mittel, Min, Max) plus Dauer je Seite in der Seiten-Logzeile | OF-03 |
| Seitengrößen-Abweichung (Abschneiden, Stauchen oder DPI-Fehler?) | beobachtet, Ursache unbekannt **[Scan]** | Nachmessen mit Lineal und Referenzmaß | OF-05 |
| 600 dpi: Dauer, Dateigröße, Header, Subsampling | unbekannt **[offen]** (Befehl bekannt **[App/s400w]**) | 600-dpi-Scan am echten Gerät | OF-06 |
| Verhalten bei niedrigem Akku | unbekannt **[offen]** (Antwort bekannt **[App/s400w]**) | Gerät leerlaufen lassen | OF-10 |

Die Fragen selbst — Status, Hintergründe, vorläufige Defaults — stehen ausschließlich in `docs/internal/offene-fragen.md` und werden hier nicht wiederholt. Geklärte Messwerte wandern von dort hierher.

## Quellen

- Handbuch zum S400W iScan Air (unter anderem Einschaltvorgang, SSID, Auto-Off, Clients, Reichweite) — als Quelle genannt, nicht wiedergegeben: Herstellerhandbuch, u. a. über manualslib abrufbar.
- Eigene Beobachtungen am Gerät und an echten Scans (siehe Markierungen **[Gerät]** und **[Scan]**).
- Windows-App „iScan Air" und s400w-Implementierung für alles mit **[App/s400w]** — nur Schnittstellenwissen, kein Hersteller-Code im Repo.
