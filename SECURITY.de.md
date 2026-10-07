# Sicherheit

## Eine Lücke melden

**Bitte nicht über ein öffentliches Issue.** Nutze stattdessen den privaten Meldeweg von GitHub:

**[Sicherheitslücke melden](https://github.com/digiwomb-dev/UnboundAir/security/advisories/new)**

Der Bericht ist nur für die Betreuer des Projekts sichtbar, bis eine Lösung vorliegt. Hilfreich ist, was du beobachtet hast, wie es sich auslösen lässt und welcher Stand betroffen ist (Commit oder Image-Tag).

Was du erwarten kannst, ehrlich gesagt: Das hier ist ein Hobbyprojekt einer einzelnen Person. Eine Antwort kann einige Tage dauern. Ich nenne lieber diese Wahrheit als eine Frist, die ich nicht einhalten kann.

Alles, was keine Sicherheitslücke ist – ein Fehler, eine Fehlfunktion –, gehört in ein [Issue](https://github.com/digiwomb-dev/UnboundAir/issues/new/choose).

## Welche Versionen abgedeckt sind

v1 ist noch nicht veröffentlicht. Abgedeckt ist deshalb genau der **aktuelle Stand von `main`**; es gibt keine Rückportierung in ältere Stände. Sobald es Releases gibt, steht hier eine Tabelle.

## Token und Secrets

**Kein Token gehört ins Repository.** Das paperless-API-Token kommt zur Laufzeit herein – entweder als Umgebungsvariable (`UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN`) oder, bevorzugt, als eingebundene Datei, deren Pfad über `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` bekannt gemacht wird. Der Dienst nennt den Token in keinem Log und in keiner Fehlermeldung; `PaperlessSettings` maskiert ihn auch in seiner eigenen Textdarstellung.

Die Einzelheiten – welche Quelle gewinnt, warum der Dienst ohne beide gar nicht erst startet – stehen unter „Token-Auflösung" in [`docs/de/configuration.md`](docs/de/configuration.md); wie das Secret in den Container kommt, steht in [`docs/de/operations.md`](docs/de/operations.md).

Wenn du in einem Log, einem Issue oder einem Commit versehentlich einen Token veröffentlicht hast: Ziehe ihn in paperless-ngx zurück und erzeuge einen neuen. Aus der Git-Historie ist er nicht zuverlässig zu entfernen.

## Geltungsbereich

**Dazu gehört:** der Dienst selbst, das Container-Image und die Konfiguration – insbesondere alles, was Dateien schreibt (Outbox, PDF-Erzeugung) oder Zugangsdaten verarbeitet.

**Dazu gehört nicht:** Firmware und Protokoll des Scanners. Das Gerät spannt ein eigenes WLAN auf, dessen WPA-Passwort herstellerseitig fest und im Handbuch veröffentlicht ist, und spricht darin Klartext über TCP-Port 23 – ohne Authentifizierung und ohne Verschlüsselung. Beides sind Eigenschaften des Geräts, die dieses Projekt nicht beheben kann; es kann nur mit ihnen reden. Wer in Funkreichweite des Scanners steht, kann mit ihm sprechen – deshalb beschreibt [`docs/de/operations.md`](docs/de/operations.md) einen Paketfilter, der dem fremden Netz nur das Nötige erlaubt, und ein Netzprofil, das die Standardroute des Hosts nicht umbiegt. Eine Meldung über das Gerät selbst ist kein Befund in UnboundAir.

Ebenfalls außerhalb: paperless-ngx selbst und die Container-Runtime. Beide haben eigene Meldewege.
