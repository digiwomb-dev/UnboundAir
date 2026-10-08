# UnboundAir

Macht aus einem Mustek iScan Air (S400W) einen „Einlegen und fertig"-Scanner: Blatt einlegen, der Dienst scannt von selbst, schneidet den schwarzen Rand verlustfrei ab, fügt mehrere Seiten zu einem PDF zusammen und übergibt es an ein Ausgabe-Modul. Das erste Modul lädt nach [paperless-ngx](https://docs.paperless-ngx.com/) hoch.

Kein Knopfdruck, keine Hersteller-Software, keine Windows-Anwendung. Kotlin und Spring Boot, Betrieb als Container.

[![Nightly](https://github.com/digiwomb-dev/UnboundAir/actions/workflows/nightly.yml/badge.svg)](https://github.com/digiwomb-dev/UnboundAir/actions/workflows/nightly.yml)

> **Im Aufbau – v1 ist noch nicht fertig.** Ein Überblick steht unten unter [„Stand"](#stand); woran gerade gearbeitet wird, zeigen die [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) und [Issues](https://github.com/digiwomb-dev/UnboundAir/issues) – dort steht es aus erster Hand, statt hier zu veralten.

**Die Dokumentation steht als Seite bereit: <https://digiwomb-dev.github.io/UnboundAir/>** – deutsch und englisch, durchsuchbar. Diese Datei beantwortet „was ist dieser Code"; alles zum Benutzen wohnt dort.

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
- **Betrieb als Container,** für `linux/arm64` und `linux/amd64`.

## Voraussetzungen

- Ein **Mustek iScan Air S400W** – auf andere Geräte ist nichts davon übertragbar.
- Ein **Rechner, der das WLAN des Scanners hält** (Host), mit einer **Container-Runtime** (Docker oder Podman).
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
| Container-Images für `arm64` und `amd64` | Native Image |

## Schnellstart

Voraussetzungen siehe oben. Das Image ziehen und starten — `nightly` fährt den Entwicklungsstand; Releases tragen Versionen (`1.2.0`, dazu `latest` außer bei Vorabversionen):

```sh
docker run --network=host \
  -v unboundair-outbox:/var/lib/unboundair/outbox \
  -v /pfad/zum/tokenfile:/run/secrets/paperless-token:ro \
  -e UNBOUNDAIR_OUTPUT_MODULES=paperless \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token \
  -e TZ=Europe/Berlin \
  ghcr.io/digiwomb-dev/unboundair:nightly \
  run
```

Adresse, Token-Datei und Zeitzone anpassen. Scanner-Adresse nur bei Abweichung von `192.168.18.33` (`UNBOUNDAIR_SCANNER_HOST`).

Blatt einlegen – der Dienst scannt von selbst, wartet kurz auf weitere Seiten und legt das fertige PDF in paperless-ngx ab (sichtbar dort und im Container-Log auf stdout). Kommt nichts an, hilft [`docs/de/operations.md`](docs/de/operations.md) beim Betrieb weiter — dort stehen auch die vollständigen Beispiele (Compose-Datei, Quadlet).

Und sonst: [`docs/de/configuration.md`](docs/de/configuration.md) für jede Einstellung, [`docs/de/operations.md`](docs/de/operations.md) für den echten Betrieb, [`docs/de/development.md`](docs/de/development.md) zum Bauen und Testen.

## Selbst bauen

Wer ändern statt fahren will: Image selbst bauen — wie, steht in [`docs/de/development.md`](docs/de/development.md).

## Wo was steht

- **[Dokumentation](https://digiwomb-dev.github.io/UnboundAir/)** – Schnellstart, Betrieb, alle Einstellungen, Befehle, Protokoll, Fehlersuche. Der Ort für alles, was das Produkt betrifft, in beiden Sprachen.
- **[`CONTRIBUTING.md`](CONTRIBUTING.md)** – mitarbeiten: Sprache, Commits, Git-Ablauf, Issue-Konvention.
- **[`SECURITY.md`](SECURITY.md)** – eine Sicherheitslücke melden: der private Meldeweg und der Umgang mit Token.
- **[`docs/internal/`](docs/internal/)** – Auftrag, Entscheidungs-Logbuch, offene Gerätefragen, Teststrategie. Die Arbeitsunterlagen des Projekts, nur auf Deutsch und nicht Teil der Seite.

Die Quelltexte der Doku liegen unter `docs/de/` und `docs/en/` – gelesen werden sie besser auf der Seite, dort stimmen die Querverweise und die Suche.

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

