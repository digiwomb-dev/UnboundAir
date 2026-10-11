---
title: Fehlersuche
---


Was schiefgehen kann, woran man es erkennt und was dann zu tun ist (DO-20). Jeder Eintrag nennt eine **Beobachtung**, ihre **Ursache** und die **Abhilfe**.

**Das Log auf stdout ist das Instrument.** In v1 gibt es keine Weboberfläche und keinen Health-Endpunkt; was der Dienst tut, steht im Container-Log (KL-02). Deshalb zuerst: wie es aussieht, wenn alles stimmt.

## Wie ein gesunder Lauf aussieht

**Beim Start kommen zwei Zeilen:**

```
WARN  [UnboundAirApplication] service started
INFO  [ScanLoop] scanner is reachable again
```

Dass die erste `WARN` trägt, ist kein Alarm, sondern die Senke: Die Meldungen der Befehle laufen alle über einen gemeinsamen Kanal (SV-02), und der schreibt auf dieser Stufe.

**Die zweite Zeile sagt „again", obwohl nichts vorausging** — auch beim allerersten Start. Das ist kein Hinweis auf einen verpassten Ausfall: Die erste Beobachtung gilt immer als Zustandswechsel, damit ein Dienst, der gegen ein ausgeschaltetes Gerät startet, das sofort sagt statt zu schweigen (DL-02). Ist der Scanner beim Start aus, steht hier entsprechend `scanner is offline, slowing down to PT10S`.

Was dagegen fehlt, ist Absicht — kein Banner, keine „Started in 0.4 seconds"-Zeile: Die Startmeldungen des Frameworks sind abgeschaltet, weil sie bei einem Kommandozeilen-Werkzeug die eigentliche Antwort vom Bildschirm schieben (KL-02). Bleibt es nach diesen zwei Zeilen still, klemmt also nichts.

Danach ist **Stille normal**: Der Dienst fragt den Scanner alle drei Sekunden nach seinem Status und schreibt davon nichts, weil eine Zeile pro Abfrage das Log unlesbar machen würde. Geschrieben wird nur bei einem Zustandswechsel (DL-02).

Pro gescannter Seite kommt eine Zeile mit Scan-Dauer, Übertragungsdauer, Größe und den Maßen in Millimetern nach dem Zuschnitt:

```
INFO  [ScanLoop] page 1 scanned in 8123 ms, transferred in 2311 ms, 1048576 bytes, 206.9 x 291.3 mm
```

Zwei Dinge daran sind Absicht. **Kein Zeitstempel und keine PID** — journald und jede Container-Runtime stempeln jede Zeile ohnehin, doppelt wäre nur breiter. Und die **Millimeter tragen eine Dezimalstelle**, weil sie aus Pixel ÷ DPI entstehen, ohne Umrechnung auf ein Normformat (SV-05).

**Die Millimeter oben sind an einem echten A4-Blatt gemessen, nicht gerechnet:** 206,9 × 291,3 statt 210 × 297. Dauern und Dateigröße in der Beispielzeile sind dagegen plausible Platzhalter — wie lange ein Scan dauert, ist noch nicht systematisch gemessen (OF-03). Gescannte Seiten fallen kleiner aus als das eingelegte Papier, und warum, ist offen — OF-05 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md). Wer hier Normmaße erwartet, sucht einen Fehler, den es nicht gibt.

Ist das Dokument fertig und abgeliefert, folgt `document … delivered to the output modules`, beim paperless-Modul zusätzlich `paperless-ngx accepted the document; consumption task …` mit der Task-ID aus der Antwort (AU-05).

**Damit lässt sich still-und-arbeitet von still-und-steht unterscheiden:** Kommt beim Einlegen eines Blattes keine `page`-Zeile, scannt der Dienst nicht. Kommt sie, aber kein `delivered`, hängt es an der Ausgabe.

## Der Dienst läuft, findet den Scanner aber nie

**Beobachtung:** Keine `page`-Zeile beim Einlegen. Im Log steht **genau einmal** `scanner is offline, slowing down to PT10S` und danach nichts mehr. `status` meldet einen Fehler statt eines Status.

**Ursache:** Entweder hängt der **Host** nicht im WLAN des Scanners, oder der **Container** nutzt nicht das Netz des Hosts. Beides sieht im Log gleich aus, denn für den Dienst ist ein nicht erreichbarer Scanner ein regulärer Zustand und kein Fehler (DL-02): Er fragt langsamer weiter und findet von selbst zurück, sobald das Gerät antwortet — dann steht `scanner is reachable again` da. Genau deshalb ist das Log hier so ruhig: Eine Zeile je Zustandswechsel, nicht je Fehlversuch.

**Abhilfe,** in dieser Reihenfolge:

1. Hält der Host die Verbindung? Das WLAN zum Scanner hält der Host, nicht der Container — Abschnitt 1 in [`operations.md`](operations.md).
2. Teilt der Container das Netz des Hosts? Mit Bridge-Netzwerk startet der Container und findet das Gerät nie — Abschnitt 2 in [`operations.md`](operations.md).
3. Antwortet das Gerät überhaupt? [`cli.md`](cli.md) beschreibt `status`, den Befehl, der nichts tut außer zu fragen.

**Noch nicht systematisch gemessen, und hier offen gesagt:** Bei einem laufenden Dienst blieb das Gerät über die fünf Minuten hinaus erreichbar — der 3-Sekunden-Statustakt hält es also offenbar wach. Das ist eine Beobachtung am Gerät, kein Messlauf: OF-01 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) bleibt darum als `beobachtet` geführt, nicht als geklärt. Geht das Gerät nach einiger Ruhe trotzdem offline, ist das also womöglich gar kein Fehler.

## Eine Einstellung wirkt nicht

**Beobachtung:** Eine `UNBOUNDAIR_…`-Variable ist gesetzt, der Dienst verhält sich aber wie ohne. Keine Fehlermeldung, kein Hinweis im Log.

**Ursache:** Die Schreibweise aus KL-01. Aus jedem Punkt wird ein Unterstrich, **jeder Bindestrich entfällt ersatzlos**. `UNBOUNDAIR_POLL_INTERVAL` ist deshalb kein Tippfehler, den Spring meldet, sondern ein Name, den es nicht kennt — die Einstellung bleibt stillschweigend auf ihrem Default.

**Abhilfe:** `UNBOUNDAIR_POLLINTERVAL`. Die vollständige Zuordnung jeder Einstellung steht in [`configuration.md`](configuration.md).

Das ist die teuerste Falle dieses Projekts, weil sie nichts kostet außer Zeit: Es gibt keine Meldung, an der man sie erkennt. Wer eine Einstellung ändert und keine Wirkung sieht, prüft zuerst den Variablennamen.

## Der Dienst startet gar nicht

**Beobachtung:** Der Container endet sofort mit einer der drei Meldungen:

```
paperless: no token configured -- set the token or the path to a token file (AU-05)
paperless: token file /run/secrets/paperless-token is not readable; check that it exists and is accessible (AU-05)
paperless: token file /run/secrets/paperless-token is empty; the file must contain the token (AU-05)
```

**Ursache:** Das Token ist weder direkt gesetzt noch als lesbare, nicht-leere Datei vorhanden (AU-05). Dass der Dienst dann gar nicht startet, ist Absicht und kein Härtefall — welche Quelle gegen welche gewinnt und warum, steht unter „Token-Auflösung" in [`configuration.md`](configuration.md).

**Für die Fehlersuche zählt vor allem die zweite und dritte Meldung:** Eine gesetzte Token-Datei gewinnt auch gegen einen gleichzeitig gesetzten Token. Ist die Datei nicht eingebunden oder leer, hilft ein daneben stehender `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN` also nicht — der Start bricht trotzdem ab. Das ist der häufigste Stolperstein beim Umstellen von der Variable auf ein Secret.

**Abhilfe:** Prüfen, ob die Datei im Container wirklich an dem Pfad liegt, den `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` nennt, und ob der Dienstnutzer sie lesen darf. Die Meldung nennt den Pfad — und ausdrücklich nie den Inhalt, damit kein Teil des Secrets in ein Log gelangt.

## Dokumente stauen sich und kommen nicht an

**Beobachtung:** `page`-Zeilen kommen, `document … delivered …` bleibt aus. In der Outbox liegen Verzeichnisse, die nicht verschwinden.

**Ursache:** Das Ziel nimmt sie nicht an — paperless ist nicht erreichbar, die Adresse ist falsch oder das Token gilt nicht mehr.

**Abhilfe:** Ziel reparieren. **Es geht nichts verloren:** Die Outbox behält jedes Dokument, bis ein Modul den Empfang bestätigt, und wiederholt mit wachsendem Abstand — erst nach 30 Sekunden, dann doppelt so lang, gedeckelt bei einer Stunde, unbegrenzt oft (AU-04). Ist das Ziel wieder da, läuft der Stau von selbst ab.

**Eine Ausnahme, die zählt:** Ohne dauerhaftes Volume auf dem Outbox-Pfad ist nach einem Neustart alles weg, was noch nicht zugestellt war. Deshalb verlangt [`operations.md`](operations.md) das Volume, und zwar nicht als Empfehlung.

## `color-mode = bw` schlägt fehl

**Beobachtung:** In `gray` und `color` entstehen PDFs, mit `bw` nicht. Im Log steht `closing the batch failed: …` mit einer Meldung über das Programm `jbig2`.

**Ursache:** Das Programm `jbig2` fehlt oder scheitert. Eine 1-Bit-Seite erreicht das PDF als JBIG2 (SV-08), und das kodiert ausschließlich dieses externe Programm — der Encoder wird nicht nachgebaut. Das Programm heißt `jbig2`, nicht `jbig2enc`; letzteres ist der Name des Quellpakets.

**Abhilfe:** Im mitgelieferten Container-Image ist `jbig2` enthalten — dort tritt das nicht auf. Außerhalb des Images ist es ein eigenes Paket, das zu installieren ist.

**Die Seiten sind dabei nicht verloren.** Ein gescheitertes Schließen lässt den Batch offen und beendet den Dienst nicht: Die Seiten bleiben liegen, der Loop fragt weiter. Ist das Programm nachinstalliert, schließt der nächste Auslöser den Batch samt der bereits gescannten Seiten.

## Eine Seite ist nicht zugeschnitten

Das sind **zwei verschiedene Dinge**, und sie sehen im Ergebnis ähnlich aus.

**Kein schwarzer Rand gefunden.** Im Log steht `no paper found in …, keeping the page uncropped`. Das ist **normales Geräteverhalten** und kein Fehler: Der Scanner erzeugt nicht immer einen Rand, und SV-01 verlangt für diesen Fall ausdrücklich, dass die Datei bytegleich übernommen wird. Eine unbeschnittene Seite ist dann das richtige Ergebnis.

**Der Zuschnitt wurde verweigert.** Im Log steht `implausible paper box 412x3380 px in …, keeping the page uncropped` — mit den Maßen des verworfenen Rahmens. Die Plausibilitätsprüfung aus SV-02 hat ein Ergebnis abgelehnt, weil der gefundene Rahmen weniger als 10 % der Bildfläche einnahm oder länger als 6-mal so lang wie breit war, und die Seite lieber ganz gelassen, als sie zu ruinieren. Diese zwei Schwellen sind im Code festgelegt und ausdrücklich keine Einstellungen — es gibt also keinen Schalter, an dem man hier dreht.

**Die genannten Pixelmaße sind der Hinweis:** Sie zeigen, *was* erkannt wurde. Ein sehr schmaler oder sehr kleiner Rahmen bedeutet meist, dass sich Papier und Hintergrund nicht trennen ließen — etwa bei einer sehr dunklen Vorlage.

**Abhilfe** im zweiten Fall: Vorlage heller oder gerader einlegen und erneut scannen. Ist der richtige Zuschnitt von Hand erkennbar, lässt sich die Seite mit `crop` nachbearbeiten — der Befehl braucht keinen Scanner ([`cli.md`](cli.md)). Für den Vergleich hilft `--keep-raw`: Dann liegt das Rohbild neben dem Ergebnis.

**Nicht zu verwechseln mit der Seitengröße:** Ein PDF, das bei A4 nur 206,9 mm breit ist, hat keinen Zuschnittfehler — siehe „Wie ein gesunder Lauf aussieht" oben und OF-05. Ein Zuschnittproblem zeigt sich an einem sichtbaren schwarzen Rand oder an einer der beiden Log-Zeilen, nicht an Millimetern unter dem Normmaß.

## Der Scanner schaltet mitten im Stapel ab

**Beobachtung:** Nach einer Pause im Stapel ist das Dokument plötzlich fertig, und die danach eingelegten Blätter landen in einem neuen.

**Ursache:** Das Gerät ist offline gegangen. Für den Dienst ist das ein **regulärer Auslöser**, den Batch zu schließen, und kein Fehler (DL-04): Offline heißt „der Vorgang ist zu Ende", also wird das PDF gebaut und übergeben.

**Warum es offline ging, ist nicht in jedem Fall klar.** Laut Handbuch schaltet sich das Gerät nach etwa fünf Minuten ohne Aktion ab. Ob der 3-Sekunden-Statustakt des Dienstes als „Aktion" gilt und das verhindert, ist **nicht systematisch gemessen** — OF-01 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md). Bei einem laufenden Dienst wurde das Gerät über fünf Minuten hinaus wach gehalten; verlässt sich darauf aber niemand, denn die naheliegendere Ursache ist meist die einfachere: Das Gerät war von Hand ausgeschaltet, der Akku leer, oder das WLAN weg.

**Abhilfe:** keine nötig — das Dokument ist vollständig. Sollen mehr Seiten in ein Dokument, müssen sie innerhalb des Zeitfensters eingelegt werden (`unboundair.batch-timeout`, Default 20 Sekunden, siehe [`configuration.md`](configuration.md)).

**Beim Beenden passiert dasselbe absichtlich:** Der offene Batch wird noch abgeschlossen (DL-07). Dafür muss der Stop-Timeout der Container-Runtime reichen — ein laufender Scan kann bis zu 60 Sekunden dauern, und ein zu kurzer Timeout schneidet den Batch ab, statt ihn zu schließen. Die Zahl steht in [`operations.md`](operations.md).

## Eine Seite fehlt im Dokument

**Beobachtung:** Das PDF hat eine Seite weniger als Blätter eingelegt wurden. Im Log steht `page discarded: …`.

**Ursache:** Diese Seite ist beim Scannen oder Verarbeiten fehlgeschlagen. Sie wird verworfen und protokolliert, der Batch bleibt offen (DL-05) — eine kaputte Seite beendet kein Dokument.

**Abhilfe:** Blatt erneut einlegen. Solange das Zeitfenster noch läuft, landet es im selben Dokument.

## Die Seite ist an den Rändern abgeschnitten oder hat einen breiten weißen Rand

**Beobachtung:** Der Scan ist an den Seiten beschnitten, oder er steht mit einem breiten weißen Rand auf der Seite — obwohl Vorlage und eingestelltes Format eigentlich zusammenpassen sollten.

**Ursache:** Die eingestellte Seitengröße (`page-size`) passt nicht zur Vorlage. Mit einem Zielmaß wird die Seitenbox auf dieses Maß gesetzt und der Scan **unskaliert zentriert** hineingelegt — er wird weder eingepasst noch gestreckt (SV-09). Ist die Box kleiner als der Scan, ragt er über und ist dort nicht sichtbar; ist sie größer, bleibt der Rest leer.

**Abhilfe:** Das passende Format einstellen, ein freies Maß in Millimetern angeben oder `off` wählen — dann gilt wieder die Scan-eigene Größe aus Pixeln und DPI (SV-05). Welche Werte `page-size` kennt, steht in [`configuration.md`](configuration.md).

**Kein Fehler, sondern erwartet:** Ein A4-Scan füllt eine A4-Box nie exakt aus. Das Gerät liefert kleinere Maße als das eingelegte Papier (A4 misst etwa 206,9 × 291,3 mm statt 210 × 297 mm) — beobachtet, Ursache unbekannt, siehe OF-05 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md). Ein schmaler Rand um den Scan auf einer A4-Box ist also normal und kein Defekt.

## Ein quer eingelegtes Blatt steht aufrecht — oder passt gar nicht in den Einzug

Das sind **zwei verschiedene Dinge**, und nur eines davon lässt sich einstellen.

**Aufrecht statt quer ist Absicht.** **Beobachtung:** Ein quer eingelegtes Blatt steht aufrecht auf der Seite, mit breitem Rand oben und unten. **Ursache:** Das eingestellte Format gilt wie geschrieben — der Scan wird unskaliert zentriert hineingelegt, nie gedreht und nie eingepasst (SV-09). Drehen gehört bewusst nicht zu v1 (siehe „End-Seitengröße einstellbar, unskaliert zentriert (SV-09)" in [`../internal/entscheidungen.md`](../internal/entscheidungen.md)); im paperless-Pfad übernimmt paperless das Geraderücken per OCRmyPDF. **Abhilfe:** Eine Quer-Box ausdrücklich wählen — die Voreinstellung `a6-landscape` oder ein freies Maß wie `210x148mm` — oder `off` für die Scan-eigene Größe (SV-05).

**Passt nicht durch den Einzug, hilft keine Einstellung.** **Beobachtung:** Ein breites Blatt lässt sich gar nicht erst einziehen. **Ursache:** Das ist eine Gerätegrenze, keine Einstellung: Die beobachtete Bildbreite endet bei etwa 208,6 mm (A4-Blatt, ca. 2464 px bei 300 dpi) **[Scan]** — Messwert in [`hardware.md`](hardware.md). Ein A4-Blatt quer (297 mm) reicht hardwareseitig nie bis ins PDF. **Abhilfe:** keine — was den Einzug nicht passiert, erreicht keine Einstellung. **Ehrlich gesagt:** Etwa 208,6 mm ist die beobachtete Bildbreite aus 1–2 Scans, keine vermessene Papiergrenze des Einzugs. Ob ein A5-Blatt quer (210 mm) noch durchpasst, ist ungemessen — dazu wird hier in keine Richtung etwas behauptet.

## Was diese Seite nicht beantwortet

**Verhalten bei niedrigem Akkustand.** Die Antwort `battlow` ist aus der Referenz-App bekannt, am Gerät aber nie aufgetreten — OF-10 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md). Ob das Gerät dann noch scannt, ob die Meldung einmal oder dauernd kommt und ob ein Scan mittendrin abbricht, ist ungemessen. Der Pfad ist gebaut und getestet (SC-05), beschrieben wird er hier nicht — was nicht gemessen ist, wird in diesem Projekt nicht behauptet.

**Häufige `devbusy`-Antworten.** Ob das Gerät den Drei-Sekunden-Takt klaglos mitmacht, ist offen — OF-02 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md). `devbusy` führt nicht zum Abbruch; sollte es gehäuft auftreten, ist `unboundair.poll-interval` der Hebel.

Beide Punkte klärt der Befehl `measure`, der genau dafür existiert ([`cli.md`](cli.md)).
