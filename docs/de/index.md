---
title: UnboundAir
description: Macht einen Mustek iScan Air S400W zum „Einlegen und fertig"-Scanner — ohne Knopfdruck, ohne Hersteller-Software.
template: splash
hero:
  tagline: Blatt einlegen, fertiges PDF in paperless-ngx. Ohne Knopfdruck, ohne Hersteller-Software.
  actions:
    - text: Schnellstart
      link: /UnboundAir/de/quickstart/
      icon: right-arrow
      variant: primary
    - text: Betrieb
      link: /UnboundAir/de/operations/
      icon: document
      variant: minimal
    - text: Repository
      link: https://github.com/digiwomb-dev/UnboundAir
      icon: github
      variant: minimal
---


## Was es ist

Ein Dienst, der einen **Mustek iScan Air (S400W)** in einen „Einlegen und fertig"-Scanner verwandelt. Blatt einlegen — der Dienst bemerkt es, scannt von selbst, schneidet den schwarzen Rand verlustfrei ab, fügt mehrere Seiten zu einem PDF zusammen und lädt es in paperless-ngx.

Kein Knopfdruck am Gerät, keine Hersteller-Software, keine Windows-Anwendung. Läuft als Container.

Zwei Dinge sind nicht verhandelbar: **Es wird nie neu komprimiert** — Zuschnitt und Graustufen laufen ausschließlich über `jpegtran`, die JPEGs wandern unverändert ins PDF. Und **am Protokoll wird nichts erfunden**: Was über das Gerät nicht bekannt ist, wird konfigurierbar gebaut und als offene Frage festgehalten, statt geraten zu werden.

## Für wen

Für wen **genau dieses Gerät** besitzt. Der Scanner spricht ein eigenes, undokumentiertes Protokoll, das hier nachgebaut wurde — nichts davon überträgt sich auf andere Scanner. Wer ein anderes Modell hat, findet hier kein Werkzeug, sondern höchstens eine Fallstudie.

Gebraucht werden außerdem ein Rechner, der das WLAN des Scanners hält, eine Container-Runtime und eine paperless-ngx-Instanz mit API-Token.

## Wie weit es ist

**v1 ist noch nicht fertig.** Was läuft: Scannen ohne Knopfdruck, verlustfreier Zuschnitt, Graustufen/Farbe/1-Bit, mehrseitige PDFs, Zeitfenster, Outbox mit Wiederholungen über Neustarts hinweg, Upload nach paperless-ngx, Container-Images für `arm64` und `amd64`.

Was noch nicht: Weboberfläche, Drehen und Geraderichten, weitere Ausgabe-Module, Native Image.

Woran gerade gearbeitet wird, steht in den [Meilensteinen](https://github.com/digiwomb-dev/UnboundAir/milestones) und [Issues](https://github.com/digiwomb-dev/UnboundAir/issues) — aus erster Hand, statt hier zu veralten.

## Wo anfangen

- **[Schnellstart](quickstart.md)** — von nichts zum ersten PDF in paperless, in fünf Schritten.
- **[Betrieb](operations.md)** — was auf dem Host gelten muss, wenn es dauerhaft laufen soll.
- **[Konfiguration](configuration.md)** — jede Einstellung mit Default und Umgebungsvariable.
- **[Fehlersuche](troubleshooting.md)** — wenn nichts ankommt oder eine Einstellung nicht wirkt.
