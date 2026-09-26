# UnboundAir

Macht aus einem Mustek iScan Air (S400W) einen „Einlegen und fertig"-Scanner: Blatt einlegen, der Dienst scannt von selbst, schneidet den schwarzen Rand verlustfrei ab, fügt mehrere Seiten zu einem PDF zusammen und übergibt es an ein Ausgabe-Modul. Das erste Modul lädt nach [paperless-ngx](https://docs.paperless-ngx.com/) hoch.

Kein Knopfdruck, keine Hersteller-Software, keine Windows-Anwendung. Kotlin und Spring Boot, Betrieb als Container.

> **Im Aufbau – Meilenstein 2 von 5, Zuschnitt und Graustufen stehen.** Aus Meilenstein 1 stehen Gradle-Projekt, Scanner-Client, Fake-Scanner und die Befehle `status` und `scan`; Meilenstein 2 hat verlustfreien Zuschnitt und Graustufen ergänzt – offen sind dort noch die Befehle `crop` und `scan --out`. Dazwischen lag der Meilenstein [`top tier testing`](https://github.com/digiwomb-dev/UnboundAir/milestone/7), der die Testbasis vertieft hat: Property-, Golden-Master-, Contract- und Wächter-Tests plus einen Mutationslauf über die Kern-Pakete. Der aktuelle Stand steht in den [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) und [Issues](https://github.com/digiwomb-dev/UnboundAir/issues).

## Was es können soll

1. Scanner einschalten, der Rechner verbindet sich mit dessen WLAN.
2. Blatt einlegen – der Dienst erkennt das und scannt ohne weiteres Zutun.
3. Weitere Blätter innerhalb eines Zeitfensters gehören zum selben Dokument.
4. Zeitfenster abgelaufen oder Scanner aus: PDF bauen und an die konfigurierten Ausgabe-Module übergeben.

Zwei Dinge sind dabei nicht verhandelbar: **Es wird nie neu komprimiert** – Zuschnitt und Graustufen laufen ausschließlich über `jpegtran`, die JPEGs wandern unverändert ins PDF. Und **am Protokoll wird nichts erfunden**: Was über das Gerät nicht bekannt ist, wird konfigurierbar gebaut und in `docs/offene-fragen.md` geführt, statt geraten zu werden.

## Wegweiser durch die Dokumentation

Die Doku ist auf Deutsch. Je nachdem, was du vorhast:

| Du willst … | Lies |
|---|---|
| wissen, was gebaut wird und warum | [`docs/plan.md`](docs/plan.md) – Auftrag, feste Entscheidungen, alle Anforderungen mit IDs und Abnahmekriterien |
| den aktuellen Stand sehen | [GitHub-Issues](https://github.com/digiwomb-dev/UnboundAir/issues) und [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) – offene Aufgaben, was in Arbeit und was erledigt ist |
| selbst bauen und testen | [`docs/entwicklung.md`](docs/entwicklung.md) – Dev Container, Build, Testlauf |
| wissen, wie getestet wird | [`docs/teststrategie.md`](docs/teststrategie.md) – die acht Testschichten, die Werkzeuge je Schicht und die Gründe dafür |
| wissen, warum etwas so entschieden wurde | [`docs/entscheidungen.md`](docs/entscheidungen.md) – Begründungen zu den festen Entscheidungen, inklusive der gemessenen Zahlen |
| wissen, was am Gerät noch unklar ist | [`docs/offene-fragen.md`](docs/offene-fragen.md) – offene Punkte mit Status, Herkunft und dem Umgang damit im Code |
| am Projekt mitarbeiten | [`AGENTS.md`](AGENTS.md) – Arbeitsweise, Leitplanken, Regeln |

Weitere Dateien entstehen später: `protokoll.md` und `hardware.md` (Gerät und Protokoll), `betrieb.md` (Container-Betrieb) und `ausgabe-module.md` (Modul-Schnittstelle). Sie sind in `plan.md` als Anforderungen DO-01 bis DO-05 beschrieben und gehören zu Meilenstein 5.

## Warum es das gibt

Der Scanner ist WLAN-only und spricht ein eigenes, undokumentiertes TCP-Protokoll auf Port 23. Die mitgelieferte Windows-Anwendung verlangt für jede Seite Klicks; SANE und eSCL helfen nicht weiter, weil das Gerät keines davon spricht.

Das Protokollwissen stammt aus eigener Analyse am Gerät, aus dem Handbuch und aus [AirScan](https://github.com/markosjal/AirScan), das die CC0-lizenzierte Implementierung s400w enthält. Übernommen wurde daraus nur Protokollwissen, kein Code – und kein Hersteller-Code.

## Stand der Technik

- Kotlin, Spring Boot, Gradle mit Kotlin DSL
- Apache PDFBox für die PDF-Erzeugung
- `jpegtran` aus libjpeg-turbo für verlustfreie Bildoperationen
- Läuft als Container; entwickelt und getestet wird ausschließlich gegen einen Fake-Scanner

Die genauen Versionen stehen in `docs/plan.md` unter „Feste Entscheidungen".
