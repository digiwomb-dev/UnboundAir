# UnboundAir

Macht aus einem Mustek iScan Air (S400W) einen „Einlegen und fertig"-Scanner: Blatt einlegen, der Dienst scannt von selbst, schneidet den schwarzen Rand verlustfrei ab, fügt mehrere Seiten zu einem PDF zusammen und übergibt es an ein Ausgabe-Modul. Das erste Modul lädt nach [paperless-ngx](https://docs.paperless-ngx.com/) hoch.

Kein Knopfdruck, keine Hersteller-Software, keine Windows-Anwendung. Kotlin und Spring Boot, Betrieb als Container.

> **Im Aufbau – Meilenstein 1 von 5.** Es gibt bisher nur Dokumentation und die Dev-Container-Definition. Noch kein lauffähiges Programm, kein Gradle-Projekt, keine Installationsanleitung. Der aktuelle Stand steht in [`docs/meilenstein-1.md`](docs/meilenstein-1.md).

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
| wissen, was gebaut wird und warum | [`docs/plan.md`](docs/plan.md) – Auftrag, feste Entscheidungen, alle Anforderungen mit IDs und Abnahmekriterien, Meilensteine |
| den aktuellen Stand sehen | [`docs/meilenstein-1.md`](docs/meilenstein-1.md) – Aufgaben, Testpunkte und was zuletzt tatsächlich getestet wurde |
| selbst bauen und testen | [`docs/entwicklung.md`](docs/entwicklung.md) – Dev Container, Build, Testlauf |
| wissen, was am Gerät noch unklar ist | [`docs/offene-fragen.md`](docs/offene-fragen.md) – offene Punkte mit Status, Herkunft und dem Umgang damit im Code |
| am Projekt mitarbeiten | [`AGENTS.md`](AGENTS.md) – Arbeitsweise, Leitplanken, Regeln |

Weitere Dateien entstehen später: `protokoll.md` und `hardware.md` (Gerät und Protokoll), `betrieb.md` (Container-Betrieb), `ausgabe-module.md` (Modul-Schnittstelle) und `entscheidungen.md` (Begründungen). Sie sind in `plan.md` als Anforderungen DO-01 bis DO-06 beschrieben und gehören zu Meilenstein 5.

## Warum es das gibt

Der Scanner ist WLAN-only und spricht ein eigenes, undokumentiertes TCP-Protokoll auf Port 23. Die mitgelieferte Windows-Anwendung verlangt für jede Seite Klicks; SANE und eSCL helfen nicht weiter, weil das Gerät keines davon spricht.

Das Protokollwissen stammt aus eigener Analyse am Gerät, aus dem Handbuch und aus [AirScan](https://github.com/markosjal/AirScan), das die CC0-lizenzierte Implementierung s400w enthält. Übernommen wurde daraus nur Protokollwissen, kein Code – und kein Hersteller-Code.

## Stand der Technik

- Kotlin, Spring Boot, Gradle mit Kotlin DSL
- Apache PDFBox für die PDF-Erzeugung
- `jpegtran` aus libjpeg-turbo für verlustfreie Bildoperationen
- Läuft als Container; entwickelt und getestet wird ausschließlich gegen einen Fake-Scanner

Die genauen Versionen stehen in `docs/plan.md` unter „Feste Entscheidungen".
