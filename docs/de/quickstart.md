---
title: Schnellstart
---


Von nichts zum ersten PDF in paperless-ngx (DO-18). Diese Seite bringt **einen** Weg zum Laufen — nicht den sauberen Dauerbetrieb. Was für den echten Betrieb dazugehört, steht in [`operations.md`](operations.md), und am Ende dieser Seite ist gesagt, wann du dort weiterliest.

## 1. Was du brauchst

- Einen **Mustek iScan Air S400W**. Nichts davon überträgt sich auf andere Scanner — das Gerät spricht ein eigenes Protokoll, das in diesem Projekt nachgebaut wurde.
- Einen **Rechner, der im WLAN des Scanners hängt**, mit einer **Container-Runtime** (Docker oder Podman).
- Eine **paperless-ngx-Instanz mit API-Token**. Das ist in v1 das einzige Ausgabe-Modul; ohne Token startet der Dienst nicht.

**Die eine Voraussetzung, die am häufigsten zuerst beißt:** Die WLAN-Verbindung zum Scanner hält der **Host**, nicht der Container. Der Scanner spannt sein eigenes WLAN auf, der Host verbindet sich dorthin, und der Container nutzt diese Verbindung mit. Deshalb startet unten `--network=host` — mit einem Bridge-Netzwerk läuft der Container an und findet das Gerät nie.

## 2. Token in eine Datei legen

```sh
printf '%s' 'TOKEN-HIER-EINSETZEN' | install -m 600 /dev/stdin /srv/unboundair/paperless-token.txt
```

Eine Datei und keine Umgebungsvariable, weil das die Form ist, die der Rest der Dokumentation benutzt — und weil ein Token in einer Variablen in jeder Prozessliste und jedem `inspect` steht. `printf` statt `echo`, damit kein Zeilenumbruch hineingerät.

## 3. Container starten

```sh
docker run --network=host \
  -v unboundair-outbox:/var/lib/unboundair/outbox \
  -v /srv/unboundair/paperless-token.txt:/run/secrets/paperless-token:ro \
  -e UNBOUNDAIR_OUTPUT_MODULES=paperless \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token \
  -e TZ=Europe/Berlin \
  ghcr.io/digiwomb-dev/unboundair:nightly \
  run
```

**Was du anpassen musst:** die Adresse deiner paperless-Instanz, den Pfad der Token-Datei links vom Doppelpunkt und die Zeitzone. Die Scanner-Adresse nur, wenn sie von `192.168.18.33` abweicht (`UNBOUNDAIR_SCANNER_HOST`).

**Was die Zeilen tun, die nicht nach Konfiguration aussehen:** `--network=host` gibt dem Container die WLAN-Verbindung des Hosts. Das Volume auf die Outbox ist der Grund, warum ein Neustart keine Dokumente verliert — ohne dauerhaftes Volume ist alles weg, was noch nicht zugestellt war. Und `run` am Ende ist der Befehl: Ohne ihn gibt es nur die Hilfe und Exit-Code 1, denn kein Befehl heißt nicht „nimm den Dienst" ([`cli.md`](cli.md)).

**Der Tag `nightly` ist der Entwicklungsstand.** Releases tragen Versionen (`1.2.0`, dazu `latest` außer bei Vorabversionen).

**Eine Falle, die nichts kostet außer Zeit:** Bei den `UNBOUNDAIR_…`-Variablen entfällt **jeder Bindestrich ersatzlos**. `UNBOUNDAIR_POLL_INTERVAL` wird stillschweigend ignoriert, richtig ist `UNBOUNDAIR_POLLINTERVAL`. Es gibt keine Fehlermeldung dafür — die vollständige Regel steht in [`configuration.md`](configuration.md).

## 4. Blatt einlegen

Jetzt ist nichts weiter zu tun: Der Dienst fragt den Scanner alle drei Sekunden nach seinem Status, bemerkt das eingelegte Blatt und scannt von selbst. Kein Knopfdruck, keine Hersteller-Software.

**Zwischen den Seiten ist das Log still** — das ist richtig so. Beim Start steht dort eine Zeile `service started`, danach kommt pro gescannter Seite genau eine:

```
INFO  [ScanLoop] page 1 scanned in 8123 ms, transferred in 2311 ms, 1048576 bytes, 206.9 x 291.3 mm
```

Scan-Dauer, Übertragungsdauer, Größe und die Maße nach dem Zuschnitt. Dass ein A4-Blatt dort 206,9 × 291,3 mm misst und nicht 210 × 297, ist bekannt und kein Zuschnittfehler ([`troubleshooting.md`](troubleshooting.md)).

## 5. Wo es gelandet ist

Nach der letzten Seite wartet der Dienst **20 Sekunden** auf ein weiteres Blatt. Kommt keines, baut er das PDF und übergibt es:

```
INFO  [PaperlessModule] paperless-ngx accepted the document; consumption task 6f2a1c74-9b3e-4d58-9c21-7a5e0f3b8d44
INFO  [OutboxRunner] document 1758545700123 delivered to the output modules
```

Danach steht das Dokument in paperless, als `scan-20260922-143500.pdf`. Die `consumption task` ist paperless' eigene Verarbeitung — die läuft dort noch kurz weiter. Die lange Zahl ist die Kennung in der Outbox; sie ist der Startzeitpunkt des Dokuments in Millisekunden und taucht nur im Log und im Dateisystem auf.

**Das Zeitfenster ist der ganze Trick mit mehrseitigen Dokumenten:** Jedes Blatt, das innerhalb dieser 20 Sekunden nach der letzten Seite eingelegt wird, gehört zum **selben** PDF. Wer ein zehnseitiges Dokument will, legt zehn Blätter zügig nacheinander ein. Wer zwei getrennte Dokumente will, wartet dazwischen. Die Dauer ist einstellbar (`unboundair.batch-timeout`), und sie ist vorläufig — der endgültige Wert kommt aus einer Messung am echten Gerät.

Schaltet sich der Scanner zwischendurch ab, schließt das das Dokument ebenfalls. Das ist kein Fehler, sondern das vorgesehene Ende eines Vorgangs.

## Von hier weiter

Dieser Weg läuft — für den Dauerbetrieb fehlt aber noch etwas.

- **[`operations.md`](operations.md)** — was auf dem Host gelten muss: WLAN-Profil, das die Standardroute nicht umbiegt, Paketfilter, Stop-Timeout, damit ein laufender Scan beim Beenden noch fertig wird, und Updates. **Das ist die Seite, die du als Nächstes brauchst,** wenn das hier funktioniert hat.
- **[`configuration.md`](configuration.md)** — jede Einstellung mit Default und Umgebungsvariable. Absichtlich steht auf dieser Seite keine Einstellungstabelle: Eine zweite würde auseinanderlaufen.
- **[`troubleshooting.md`](troubleshooting.md)** — wenn nichts ankommt, eine Einstellung nicht wirkt oder der Dienst gar nicht startet.
- **[`cli.md`](cli.md)** — die anderen vier Befehle, etwa `status` zum Prüfen der Verbindung und `crop` zum Zuschneiden ohne Gerät.
