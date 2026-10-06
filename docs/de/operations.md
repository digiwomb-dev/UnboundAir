# Betrieb

Der Dienst läuft als Container (DO-03). Diese Datei beschreibt, was dafür auf dem Host und am Container gelten muss — unabhängig von einer bestimmten Container-Runtime. Befehle unten sind Beispiele, jeweils als solche markiert.

> **Hinweis zum Stand (Meilenstein 5):** Wohin Container-Images veröffentlicht werden (Registry, Image-Name) ist noch nicht entschieden; ein konkretes Deployment-Beispiel kommt erst mit Meilenstein 6. Wo unten ein Image-Name stehen müsste, steht deshalb ein Platzhalter.

## 1. Host-Voraussetzungen: Scanner-WLAN, NetworkManager, nftables

Die WLAN-Verbindung zum Scanner hält der Host, nicht der Container. Der Scanner spannt sein eigenes WLAN auf; der Host verbindet sich dorthin, der Container nutzt diese Verbindung des Hosts mit (siehe „Netzwerk").

**NetworkManager-Profil für das Scanner-WLAN.** Das Profil ist an die WLAN-Schnittstelle zum Scanner gebunden und verbindet sich automatisch wieder — ohne Begrenzung der Versuche —, ohne die Standardroute des Hosts umzubiegen:

- an `wlan0` gebunden
- `connection.autoconnect yes`
- `connection.autoconnect-retries 0`
- `ipv4.never-default yes`

`never-default` ist der Punkt, der alles zusammenhält: Der Scanner verteilt per DHCP Adressen aus einem privaten Netz, aber der Weg ins Internet bleibt auf der anderen Schnittstelle des Hosts. Ohne diese Einstellung würde jeder Verbindungsaufbau zum Scanner die Standardroute klauen.

**Paketfilter (nftables) auf `wlan0`.** Das Scanner-WLAN ist ein fremdes Netz, dem nicht mehr erlaubt wird als nötig:

- eingehend: nur `established`/`related` plus DHCP
- ausgehend: nur `192.168.18.33:23` (der Scanner: Adresse und Port nach SC-06) plus DHCP
- kein Forwarding

Damit kann auf dieser Schnittstelle genau eines passieren: Der Dienst spricht mit dem Scanner, und DHCP bringt die Adresse dafür mit. Alles andere bleibt draußen.

## 2. Netzwerk: Der Container erreicht den Scanner über den Host

Der Container muss `192.168.18.33:23` über das WLAN des Hosts erreichen können. Was dafür konkret einzustellen ist, hängt von der Container-Runtime ab — die Anforderung ist runtime-neutral: Der Container teilt sich den Netzwerkzugang des Hosts so, dass diese Adresse mit diesem Port erreichbar ist.

Ein Weg dorthin ist beispielsweise das Host-Netzwerk der Runtime (z. B. mit `… --network=host …` als Beispiel, sinngemäß auch bei anderen Runtimes). Entscheidend ist nicht die Option, sondern das Ergebnis: Aus dem Container heraus ist `192.168.18.33:23` erreichbar, solange der Host selbst im Scanner-WLAN hängt. Bricht die WLAN-Verbindung des Hosts ab, ist der Scanner aus Sicht des Dienstes offline — das ist ein regulärer Zustand (DL-02, DL-04), kein Fehler.

## 3. Persistenz: Die Outbox liegt auf einem dauerhaften Volume

Fertige Dokumente liegen zuerst in der Outbox (`unboundair.outbox.path`, Default `/var/lib/unboundair/outbox`), bevor ein Ausgabe-Modul sie annimmt — erst nach Erfolg wird gelöscht (AU-04). Die Outbox muss deshalb auf einem persistenten Volume liegen, z. B. als benanntes Volume oder als eingebundener Host-Pfad auf den Outbox-Pfad im Container gemappt (konkrete Option je Runtime, sinngemäß dieselbe Abbildung).

Ohne dieses Volume gehen bei jedem Neustart alle noch nicht zugestellten Dokumente verloren: Was in der Outbox lag, aber noch kein Modul angenommen hatte, ist nach dem Neustart weg. Mit Volume überlebt die Outbox den Neustart, und die Zustellung wird wieder aufgenommen (AU-04).

## 4. Konfiguration und Secrets

**Referenz ist [`configuration.md`](configuration.md).** Diese Datei wiederholt die Einstellungstabelle bewusst nicht: Jede Einstellung mit Default und Umgebungsvariable steht dort (DO-09, KL-01), und nur dort — eine zweite Tabelle würde driften, ohne dass ein Test sie bewacht.

Im Container werden Einstellungen per `UNBOUNDAIR_…`-Umgebungsvariable gesetzt. Dabei gilt die Schreibweise aus KL-01 mit ihrer Stolperfalle: Jeder Punkt wird zum Unterstrich, jeder Bindestrich entfällt ersatzlos. `unboundair.poll-interval` heißt als Variable also `UNBOUNDAIR_POLLINTERVAL` — `UNBOUNDAIR_POLL_INTERVAL` (mit Unterstrich statt entfallenem Bindestrich) erkennt Spring stillschweigend nicht, die Einstellung bleibt dann einfach auf ihrem Default. Die vollständige Zuordnung steht in [`configuration.md`](configuration.md).

**Secrets gehören nicht in Umgebungsvariablen mit Klartext und nicht in Dateien im Image.** Das paperless-Token kommt als eingebundene Datei in den Container; ihr Pfad wird über die Umgebungsvariable `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` bekannt gemacht (AU-05, KL-01). Die Datei wird also über einen Secret-Mechanismus der jeweiligen Runtime eingebunden (Beispiel: eine Secret- oder Datei-Einbindung auf den Pfad, den die Variable nennt); der Dienst liest den Token von dort. Wie Token und Token-Datei zusammenspielen — Datei gewinnt, wenn gesetzt; ohne beides startet der Dienst gar nicht erst — steht unter „Token-Auflösung" in [`configuration.md`](configuration.md).

## 5. Beenden: Der offene Batch wird noch abgeschlossen

Beim Beenden (SIGTERM/SIGINT) schließt der Dienst den offenen Batch noch ab und übergibt ihn an die Outbox (DL-07). Was das heißt: Ein Batch, in dem bereits Seiten liegen, wird als Dokument fertig gebaut und in der Outbox abgelegt, statt es zu verwerfen. Die Outbox-Zustellung an die Module läuft danach weiter, solange der Prozess noch lebt — was beim Herunterfahren nicht mehr zugestellt wird, bleibt dank Volume liegen und wird nach dem Neustart zugestellt.

**Operative Folgerung: Der Stop-Timeout der Container-Runtime muss dafür reichen.** Ein laufender Scan kann bis zu 60 s dauern (Timeout von `jpegsize` nach SC-02) — erst danach ist die Seite vollständig und der Batch schließbar. Ist der Stop-Timeout kürzer, bricht die Runtime den Container vorher ab, und der offene Batch wird abgeschnitten statt abgeschlossen. Also: Stop-Timeout großzügig über 60 s wählen (z. B. als Beispiel `… --stop-timeout=90 …` oder die entsprechende Einstellung der verwendeten Runtime), damit ein laufender Scan noch zu Ende kommen kann.

## 6. Logs, Neustart, Update

**Logs** gehen auf stdout (KL-02, journald-freundlich). Pro Seite steht dort unter anderem Scan-Dauer, Übertragungsdauer, Größe und Maße in mm nach Zuschnitt; die Task-UUID eines paperless-Uploads (AU-05) steht ebenfalls im Log. Es wird nichts in Dateien im Container geschrieben, was man sichern müsste — die Runtime holt die Logs von stdout ab (Beispiel: `… logs …` der verwendeten Runtime).

**Neustart:** Der Dienst ist auf Neustarts ausgelegt. Die Outbox auf dem Volume (siehe „Persistenz") sorgt dafür, dass nicht zugestellte Dokumente nach dem Neustart erneut versucht werden — mit wachsendem Abstand (Start 30 s, Verdopplung, Deckel 1 h, unbegrenzte Versuche) und ohne dass dafür etwas zu tun wäre. Die Runtime sollte den Container bei Absturz neu starten (Beispiel: eine Restart-Richtlinie wie „bei Fehler neu starten"); das Polling findet danach von selbst wieder in den Scanner-Zustand zurück (DL-01, DL-02).

**Update:** Ein neues Image einspielen heißt: neuen Container mit denselben Einstellungen starten — dieselben `UNBOUNDAIR_…`-Variablen, dieselbe Token-Datei-Einbindung, dasselbe Volume auf die Outbox. Reihenfolge aus Rücksicht auf den offenen Batch: Erst den alten Container sauber stoppen (mit dem Stop-Timeout aus „Beenden", damit der Batch noch in die Outbox kommt), dann den neuen starten. Die Outbox auf dem Volume macht das Update verlustfrei: Was der alte Container noch nicht losgeworden ist, stellt der neue zu.
