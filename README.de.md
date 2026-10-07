# UnboundAir

Macht aus einem Mustek iScan Air (S400W) einen „Einlegen und fertig"-Scanner: Blatt einlegen, der Dienst scannt von selbst, schneidet den schwarzen Rand verlustfrei ab, fügt mehrere Seiten zu einem PDF zusammen und übergibt es an ein Ausgabe-Modul. Das erste Modul lädt nach [paperless-ngx](https://docs.paperless-ngx.com/) hoch.

Kein Knopfdruck, keine Hersteller-Software, keine Windows-Anwendung. Kotlin und Spring Boot, Betrieb als Container.

[![Nightly](https://github.com/digiwomb-dev/UnboundAir/actions/workflows/nightly.yml/badge.svg)](https://github.com/digiwomb-dev/UnboundAir/actions/workflows/nightly.yml)

> **Im Aufbau – v1 ist noch nicht fertig.** Ein Überblick steht unten unter [„Stand"](#stand); woran gerade gearbeitet wird, zeigen die [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) und [Issues](https://github.com/digiwomb-dev/UnboundAir/issues) – dort steht es aus erster Hand, statt hier zu veralten.

## Was es können soll

1. Scanner einschalten, der Rechner verbindet sich mit dessen WLAN.
2. Blatt einlegen – der Dienst erkennt das und scannt ohne weiteres Zutun.
3. Weitere Blätter innerhalb eines Zeitfensters gehören zum selben Dokument.
4. Zeitfenster abgelaufen oder Scanner aus: PDF bauen und an die konfigurierten Ausgabe-Module übergeben.

Zwei Dinge sind dabei nicht verhandelbar: **Es wird nie neu komprimiert** – Zuschnitt und Graustufen laufen ausschließlich über `jpegtran`, die JPEGs wandern unverändert ins PDF. Und **am Protokoll wird nichts erfunden**: Was über das Gerät nicht bekannt ist, wird konfigurierbar gebaut und in `docs/internal/offene-fragen.md` geführt, statt geraten zu werden.

## Was es kann

- **Scannen ohne Knopfdruck:** Der Dienst fragt das Gerät regelmäßig, erkennt ein eingelegtes Blatt und scannt von selbst.
- **Verlustfreier Zuschnitt:** Der schwarze Rand des Geräts fällt per `jpegtran` weg – ohne das JPEG neu zu komprimieren.
- **Graustufen, Farbe oder 1-bit-Schwarz-Weiß** (`gray`, `color`, `bw`). Die ersten beiden sind verlustfrei; `bw` ist es ausdrücklich nicht und braucht `jbig2`.
- **Mehrseitige PDFs:** Seiten, die innerhalb eines Zeitfensters eingelegt werden, landen in einem Dokument. Zeitfenster abgelaufen oder Scanner aus: Das PDF wird gebaut.
- **Outbox mit Wiederholung:** Fertige Dokumente liegen auf der Platte, bis ein Modul sie angenommen hat – ein Neustart oder ein nicht erreichbares Ziel verliert nichts.
- **paperless-ngx als Ausgabe-Modul,** über eine Schnittstelle, an die weitere Module andocken können.
- **Betrieb als Container,** derzeit für `linux/arm64`.

## Voraussetzungen

- Ein **Mustek iScan Air S400W** – auf andere Geräte ist nichts davon übertragbar.
- Ein **Rechner, der das WLAN des Scanners hält** (Host), mit einer **Container-Runtime** (Docker oder Podman). Derzeit nur `linux/arm64`.
- Eine **paperless-ngx-Instanz mit API-Token** – das einzige Ausgabe-Modul in v1. Ohne Token startet der Dienst nicht.
- Für `color-mode = bw` zusätzlich `jbig2`; im Container ist es enthalten.

Was auf dem Host einzurichten ist – WLAN-Profil, Paketfilter, Volume, Secret –, steht in [`docs/de/operations.md`](docs/de/operations.md).

## Stand

| Läuft | Noch nicht gebaut |
|---|---|
| Scannen, verlustfreier Zuschnitt, Graustufen/Farbe/1-bit | Web-UI |
| Mehrseitige PDFs, Zeitfenster, Batch-Abschluss | Drehen und Geraderücken |
| Outbox mit Wiederholung über Neustarts | `normalize` (SV-04, bewusst offen) |
| paperless-ngx-Upload | weitere Ausgabe-Module |
| Container-Image für `arm64` | Native Image, `linux/amd64` |

## Schnellstart

Voraussetzungen siehe oben. Das Image musst du (noch) selbst bauen – wie, steht in [`docs/de/development.md`](docs/de/development.md); wohin Images veröffentlicht werden, ist noch offen (siehe [`docs/de/operations.md`](docs/de/operations.md)). Wo unten ein Image-Name stehen müsste, steht deshalb ein Platzhalter.

Container starten (Beispiel, sinngemäß auch bei anderen Runtimes): Outbox auf ein dauerhaftes Volume legen, paperless-Adresse und Token-Datei mitgeben, Scanner-Adresse nur falls sie von `192.168.18.33` abweicht:

```sh
docker run --network=host \
  -v unboundair-outbox:/var/lib/unboundair/outbox \
  -v /pfad/zum/tokenfile:/run/secrets/paperless-token:ro \
  -e UNBOUNDAIR_OUTPUT_MODULES=paperless \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token \
  <image-platzhalter>
```

Blatt einlegen – der Dienst scannt von selbst, wartet kurz auf weitere Seiten und legt das fertige PDF in paperless-ngx ab (sichtbar dort und im Container-Log auf stdout). Kommt nichts an, hilft [`docs/de/operations.md`](docs/de/operations.md) beim Betrieb weiter.

Und sonst: [`docs/de/configuration.md`](docs/de/configuration.md) für jede Einstellung, [`docs/de/operations.md`](docs/de/operations.md) für den echten Betrieb, [`docs/de/development.md`](docs/de/development.md) zum Bauen und Testen.

## Wegweiser durch die Dokumentation

Die Doku ist auf Deutsch. Je nachdem, was du vorhast:

| Du willst … | Lies |
|---|---|
| wissen, was gebaut wird und warum | [`docs/internal/plan.md`](docs/internal/plan.md) – Auftrag, feste Entscheidungen, alle Anforderungen mit IDs und Abnahmekriterien |
| den aktuellen Stand sehen | [GitHub-Issues](https://github.com/digiwomb-dev/UnboundAir/issues) und [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) – offene Aufgaben, was in Arbeit und was erledigt ist |
| selbst bauen und testen | [`docs/de/development.md`](docs/de/development.md) – Dev Container, Build, Testlauf |
| eine Einstellung nachschlagen | [`docs/de/configuration.md`](docs/de/configuration.md) – jede Einstellung mit Default, Umgebungsvariable und Bedeutung |
| ein Ausgabe-Modul verstehen oder schreiben | [`docs/de/output-modules.md`](docs/de/output-modules.md) – die Modul-Schnittstelle, die Kette über die Outbox, das paperless-Modul und die Anleitung für ein eigenes Modul |
| wissen, wie getestet wird | [`docs/internal/teststrategie.md`](docs/internal/teststrategie.md) – die acht Testschichten, die Werkzeuge je Schicht und die Gründe dafür |
| wissen, warum etwas so entschieden wurde | [`docs/internal/entscheidungen.md`](docs/internal/entscheidungen.md) – Begründungen zu den festen Entscheidungen, inklusive der gemessenen Zahlen |
| wissen, was am Gerät noch unklar ist | [`docs/internal/offene-fragen.md`](docs/internal/offene-fragen.md) – offene Punkte mit Status, Herkunft und dem Umgang damit im Code |
| wissen, was der Scanner über die Leitung schickt | [`docs/de/protocol.md`](docs/de/protocol.md) – das TCP-Protokoll auf Port 23: Nachrichten, Abläufe, was gemessen und was noch offen ist |
| wissen, was die Hardware kann und was nicht | [`docs/de/hardware.md`](docs/de/hardware.md) – Gerät, WLAN-Verhalten, gemessene Scan-Eigenschaften |
| den Dienst als Container betreiben | [`docs/de/operations.md`](docs/de/operations.md) – Host-Voraussetzungen, Netzwerk, Volume, Secrets, Beenden, Logs |
| am Projekt mitarbeiten | [`CONTRIBUTING.md`](CONTRIBUTING.md) – Sprache, Commits, Git-Ablauf, Issue-Konvention, Leitplanken |
| eine Sicherheitslücke melden | [`SECURITY.md`](SECURITY.md) – der private Meldeweg, Token-Umgang, Geltungsbereich |

## Warum es das gibt

Der Scanner ist WLAN-only und spricht ein eigenes, undokumentiertes TCP-Protokoll auf Port 23. Die mitgelieferte Windows-Anwendung verlangt für jede Seite Klicks; SANE und eSCL helfen nicht weiter, weil das Gerät keines davon spricht.

Das Protokollwissen stammt aus eigener Analyse am Gerät, aus dem Handbuch und aus [AirScan](https://github.com/markosjal/AirScan), das die CC0-lizenzierte Implementierung s400w enthält. Übernommen wurde daraus nur Protokollwissen, kein Code – und kein Hersteller-Code.

## Stand der Technik

- Kotlin, Spring Boot, Gradle mit Kotlin DSL
- OpenPDF für die PDF-Erzeugung (Apache PDFBox nur als unabhängiger Prüfer in Tests)
- `jpegtran` aus libjpeg-turbo für verlustfreie Bildoperationen
- Läuft als Container; automatisierte Tests laufen offline gegen einen Fake-Scanner

Die genauen Versionen stehen in `docs/internal/plan.md` unter „Feste Entscheidungen".

## Lizenz und Herkunft

UnboundAir steht unter der Apache License 2.0 – siehe [`LICENSE`](LICENSE).

Zur Herkunft des Protokollwissens: s400w ist CC0-lizenziert, übernommen wurde daraus nur Protokollwissen, kein Code. AirScan ist als Quelle genannt. Im Repository liegt kein Hersteller-Code: keine Mustek-Binärdateien, keine Installer.

## Spenden

UnboundAir nimmt Spenden entgegen — Einzelheiten stehen im [Impressum](docs/de/legal-notice.md).
