# Offene Fragen

Was am Gerät oder am Protokoll noch nicht geklärt ist. Die Leitplanke lautet: **nichts am Protokoll erfinden**. Was hier steht, ist entweder konfigurierbar gebaut oder wartet auf eine Messung – geraten wird nichts.

## Abgrenzung zu den offenen Entscheidungen im Plan

Es gibt zwei Listen mit offenen Punkten. Der Unterschied ist, **wer sie beantworten kann**:

| | Diese Datei | `plan.md`, Abschnitt „Offene Entscheidungen" |
|---|---|---|
| Beantwortet durch | die Wirklichkeit – Messung am Gerät | den Auftraggeber – eine Ansage |
| Typische Frage | „Wie verhält sich das Gerät bei X?" | „Wollen wir X oder Y?" |
| Geklärt mit | Scanner anschließen, `measure` laufen lassen, nachmessen | Entscheidung |
| Wandert später nach | `protokoll.md`, `hardware.md` (DO-01, DO-02) | `entscheidungen.md` (DO-06) |

Beispiel: „Welches CI-System?" beantwortet kein Messgerät – das gehört in den Plan. „Kommt bei 3-Sekunden-Takt `devbusy`?" entscheidet niemand – das Gerät tut es oder tut es nicht, also gehört es hierher.

**Zweistufige Punkte** gehören in beide Listen, aber in dieser Reihenfolge: erst messen (hier), dann aus dem Messergebnis einen Wert festlegen (Plan). Betroffen sind die Defaults für `poll-interval` und `batch-timeout` (OF-01 bis OF-03) sowie die Seitengrößen-Abweichung (OF-05). Bei diesen Einträgen steht der zugehörige Plan-Punkt dabei.

Reine Produktentscheidungen stehen **nicht** hier, auch wenn sie den Code betreffen – etwa was `normalize` tun soll.

## Aufbau der Einträge

Jeder Eintrag hat eine feste ID, einen Status und eine Herkunft. IDs werden nie neu vergeben. Geklärte Fragen bleiben stehen und bekommen die Antwort dazu, damit nachvollziehbar bleibt, warum etwas so gebaut ist.

**Status:** `offen` · `beobachtet` (Hinweise da, nicht bestätigt) · `geklärt` (mit Antwort) · `verschoben` (gehört nicht hierher, ID bleibt als Wegweiser stehen)

**Herkunft:** **[Gerät]** am echten Gerät verifiziert · **[App/s400w]** aus der Windows-App bzw. s400w, am Gerät nicht getestet · **[Handbuch]** · **[Scan]** an 1–2 echten Scans beobachtet · **[Analyse]** aus den Testbildern in dieser Arbeit abgeleitet

Die meisten Punkte lassen sich erst mit dem Befehl `measure` am echten Gerät klären (BE-04; wie er aufgerufen wird, ist noch offen). Ein Gerätetest ist bisher nicht freigegeben, deshalb gelten überall vorläufige Werte.

---

## OF-01 Hält Status-Polling den Scanner wach?

**Status:** offen · **Herkunft:** [Handbuch] für das Auto-Off, Rest unbekannt

Das Gerät schaltet sich laut Handbuch nach 5 Minuten ohne Aktion ab. Unklar ist, ob eine TCP-Statusabfrage als „Aktion" zählt. Davon hängt ab, ob ein Dauerbetrieb überhaupt sinnvoll ist oder ob der Nutzer den Scanner ohnehin regelmäßig neu einschaltet.

**So gebaut:** `poll-interval` konfigurierbar, vorläufiger Default 3 s (DL-01). Offline ist ein regulärer Zustand, kein Fehler (DL-02, DL-04).

**Klärt:** `measure` – protokolliert den Zeitpunkt, ab dem das Gerät nicht mehr erreichbar ist, und den Abstand zur letzten Aktivität.

**Zweistufig:** Nach der Messung ist noch zu entscheiden, welcher Default daraus wird – siehe „Defaults" in den offenen Entscheidungen des Plans.

---

## OF-02 Kommt bei 3-Sekunden-Takt `devbusy`?

**Status:** offen · **Herkunft:** [App/s400w] – die Antwort `devbusy` ist bekannt, ihre Häufigkeit nicht

Die Mustek-App fragt den Status nicht periodisch ab, AirScan alle 8 s. Ob das Gerät bei 3 s mit `devbusy` reagiert oder das klaglos mitmacht, ist unbekannt. Falls `devbusy` häufig kommt, muss der Dienst das Intervall strecken.

**So gebaut:** `devbusy` wird als eigener Zustand behandelt und führt nicht zum Abbruch (SC-05).

**Klärt:** `measure` – zählt die `devbusy`-Antworten pro Lauf.

**Zweistufig:** Kommt `devbusy` häufig, muss anschließend entschieden werden, wie stark das Polling gestreckt wird – siehe „Defaults" in den offenen Entscheidungen des Plans.

---

## OF-03 Scan-Dauer und Abstand zwischen zwei Seiten

**Status:** offen · **Herkunft:** [Gerät] nur für die Dateigröße (A4 300 dpi ≈ 0,9 MB)

Wie lange ein Scan dauert und wie viel Zeit zwischen zwei Blättern vergeht, wenn jemand normal nachlegt, ist nicht gemessen. Daraus ergibt sich der Default für das Batch-Zeitfenster: zu kurz zerreißt Dokumente, zu lang lässt den Nutzer warten.

**So gebaut:** `batch-timeout` konfigurierbar, vorläufiger Default 20 s (DL-04).

**Klärt:** `measure` – misst Scan-Dauer, Übertragungsdauer und die Abstände zwischen den Seiten (Mittelwert, Minimum, Maximum).

**Zweistufig:** Aus den gemessenen Abständen ist anschließend der endgültige `batch-timeout` festzulegen – siehe „Defaults" in den offenen Entscheidungen des Plans.

---

## OF-04 Doppelscans direkt nach einem Scan

**Status:** offen · **Herkunft:** [Scan] Vermutung aus dem Ablauf

Möglicherweise meldet das Gerät unmittelbar nach einem Scan noch einmal kurz `scanready`, obwohl kein neues Blatt eingelegt wurde. Das würde eine Leerseite erzeugen.

**Entscheidung:** Ein Doppelscan-Schutz wird in v1 **nicht** gebaut – solange nicht gemessen ist, ob das Problem überhaupt auftritt, wäre jede Sperrzeit geraten.

**Klärt:** `measure` – erkennt aufeinanderfolgende Scans mit auffällig kurzem Abstand.

---

## OF-05 Seitengrößen weichen vom Papierformat ab

**Status:** beobachtet · **Herkunft:** [Scan] und [Analyse]

Die gescannten Seiten sind kleiner als das eingelegte Papier:

| Vorlage | erwartet | gemessen | Quelle |
|---|---|---|---|
| A4 | 210 × 297 mm | 206,9 × 291,3 mm | [Scan] |
| DL-Kuvert | 110 × 220 mm | 103,0 × 211,2 mm | [Scan] |

Unklar ist, ob der Scanner am Rand etwas abschneidet, ob der Einzug das Blatt staucht, oder ob die DPI-Angabe im JPEG-Header nicht der tatsächlichen optischen Auflösung entspricht. Solange das offen ist, kann aus den Pixeln keine verlässliche physische Größe abgeleitet werden.

**Zusätzliche Beobachtung [Analyse]:** Das Testbild `din_a4_300dpi_raw.jpg` ist roh bereits 2464 × 3425 px = 208,6 × 290,0 mm. Nach Zuschnitt müsste es also *kleiner* werden als die oben genannten 206,9 × 291,3 mm – in der Höhe ist der gemessene Wert aber **größer** als das Rohbild. Mindestens eine der beiden Zahlen stammt von einem anderen Scan oder ist falsch notiert. Das ist beim Fortschreiben zu bereinigen.

**So gebaut:** Seitengröße im PDF = Pixel ÷ DPI, ohne Umrechnung auf Normformate (SV-05). Maßgeblich ist die befohlene Auflösung; weicht der JPEG-Header ab, wird gewarnt statt abgebrochen.

**Klärt:** Nachmessen mit dem Lineal am echten Gerät, zusätzlich ein Scan mit bekanntem Referenzmaß.

**Zweistufig:** Steht die Ursache fest, ist zu entscheiden, ob der Dienst die Abweichung ausgleicht oder die Pixelmaße unverändert übernimmt – siehe „Seitengrößen-Abweichung" in den offenen Entscheidungen des Plans.

---

## OF-06 600 dpi: Dauer, Dateigröße, Header

**Status:** offen · **Herkunft:** [App/s400w] – der Befehl ist bekannt, das Ergebnis nicht

Wie lange ein 600-dpi-Scan dauert, wie groß die Datei wird und ob der JPEG-Header dann tatsächlich 600 dpi meldet, ist ungetestet. Auch das Chroma-Subsampling könnte abweichen – davon hängt die iMCU-Größe und damit der Zuschnitt ab.

**So gebaut:** Die iMCU-Größe wird aus dem JPEG selbst gelesen, nicht angenommen (SV-01).

**Klärt:** Ein 600-dpi-Scan am echten Gerät.

---

## OF-07 Format der `version`-Antwort

**Status:** offen · **Herkunft:** [Gerät] für den Inhalt, [App/s400w] für das Format

Alle Status- und Bestätigungsantworten sind 11 Byte: Wort + `\x00`-Padding + `H`, etwa `nopaper\x00\x00\x00H`. Die Versionsantwort passt nicht in dieses Schema: Der Referenz-Fake sendet `NB0a.032\x00`, also 9 Byte ohne abschließendes `H`. Ob das echte Gerät es genauso macht oder ob die Referenz an dieser Stelle ungenau ist, wurde nie direkt nachgeprüft.

**So gebaut:** Der Präfix-Vergleich setzt **keine** feste Länge und kein bestimmtes Padding voraus (SC-03). Damit funktioniert der Client in beiden Fällen.

**Klärt:** Ein `status`-Aufruf am echten Gerät, bei dem die Rohbytes der Versionsantwort protokolliert werden – das ist genau das, was BE-01 ohnehin tut.

---

## OF-08 500-ms-Pause vor dem Lesen der Massendaten

**Status:** beobachtet · **Herkunft:** [App/s400w]

Die Regel lautet 200 ms vor und nach jedem Senden. Der Referenzcode weicht an zwei Stellen davon ab und wartet 500 ms: nach dem Senden von `jpegdata` und nach dem Senden von `status` in der reinen Statusabfrage. Ob das nötig ist oder nur vorsichtig, ist unbekannt.

**So gebaut:** 200 ms als Regel, 500 ms vor dem Lesen der Massendaten – als benannte Konstante mit Verweis auf diesen Eintrag. Die erprobte Wartezeit wird ohne Messung nicht gekürzt.

**Klärt:** Messreihe mit schrittweise verkürzter Pause; interessant ist, ab wann Übertragungen fehlschlagen.

---

## OF-09 Was soll `normalize` tun? – verschoben

**Status:** verschoben in `plan.md`, Abschnitt „Offene Entscheidungen"

Der Punkt stand zunächst hier, gehört aber nicht in diese Liste: Was `normalize` tun soll und mit welchem Werkzeug, beantwortet keine Messung am Gerät, sondern eine Entscheidung. Die ID bleibt stehen, damit Verweise nicht ins Leere laufen.

**So gebaut:** vorerst gar nicht. SV-04 bleibt zurückgestellt, bis Zweck und Werkzeug entschieden sind. Die Verarbeitungskette (SV-07) ist so gebaut, dass ein weiterer Schritt später ohne Umbau dazukommen kann.

---

## OF-10 Verhalten bei niedrigem Akkustand

**Status:** offen · **Herkunft:** [App/s400w]

Die Antwort `battlow` ist aus der App bekannt, wurde am Gerät aber nie ausgelöst. Unklar ist, ob das Gerät dann noch scannt, ob es die Antwort nur einmal oder dauerhaft sendet und ob ein Scan mittendrin abbricht.

**So gebaut:** Eigene Exception (SC-05); der Fake-Scanner kann `battlow` auf Wunsch senden (TE-01), damit der Pfad getestet ist.

**Klärt:** Gerät leerlaufen lassen – oder offen lassen, bis es zufällig auftritt.

---

## OF-11 Der Linter hinkt der Kotlin-Version hinterher

**Status:** beobachtet · **Herkunft:** [Analyse] – am eigenen Build beobachtet und in den Abhängigkeiten nachvollzogen

Dieses Projekt nutzt grundsätzlich die neueste stabile Kotlin-Version, derzeit 2.4.20. Linter betten den Kotlin-Compiler aber selbst ein, um Quelltext zu parsen, und liegen dabei regelmäßig mehrere Versionen zurück:

| Werkzeug | eingebetteter Kotlin-Compiler |
|---|---|
| ktlint 1.5.0 (Vorgabe des ktlint-Gradle-Plugins) | 2.1.0 |
| ktlint 1.8.0 (neueste stabile) | 2.2.21 |
| detekt 1.23.8 (neueste stabile, Februar 2025) | 2.0.21 |
| detekt 2.0.0-alpha.6 | 2.4.10 |

Zwei getrennte Probleme treffen hier zusammen:

1. **Versionsüberschreibung.** `spring-boot-dependencies` importiert das `kotlin-bom`, dieses verwaltet auch `kotlin-compiler-embeddable`, und `io.spring.dependency-management` wendet die Verwaltung auf *alle* Konfigurationen an – auch auf die des Linters. Der bekommt dadurch den Compiler des Projekts untergeschoben statt seines eigenen und stürzt mit `Extensions storage is not registered` ab.
2. **Echte Unverträglichkeit.** ktlint ist mit Kotlin 2.4 grundsätzlich nicht kompatibel (ktlint-Issue 3289, gemeldet von einem JetBrains-Compiler-Entwickler, geschlossen im Juni 2026). Der Fix steckt bisher nur in ktlint 2.0.0-ALPHA-4. Die Grenze verläuft genau zwischen Kotlin 2.4.10 (funktioniert) und 2.4.20 (bricht).

**So gebaut:** ktlint läuft über das Spotless-Plugin, das seine Werkzeuge über eine `detachedConfiguration` auflöst. Solche Konfigurationen erfasst `configurations.all {}` nicht, deshalb greift Problem 1 dort nicht. Der Linter parst dadurch bewusst mit einem älteren Compiler (2.2.21) als dem, mit dem übersetzt wird (2.4.20).

**Womit zu rechnen ist:** Solange die Differenz besteht, kann der Linter Syntax nicht verstehen, die erst nach Kotlin 2.2 hinzugekommen ist. Für die hier verwendeten Sprachmittel ist das unkritisch; fällt es doch auf, äußert es sich als Parse-Fehler in einer bestimmten Datei.

**Klärt sich,** sobald ktlint 2.x stabil ist und die Gradle-Plugins dessen neue Koordinaten unterstützen. Dann ist zu prüfen, ob der Umweg über Spotless noch nötig ist. Dieser Abstand tritt bei jedem Kotlin-Update erneut auf und ist kein einmaliges Problem.
