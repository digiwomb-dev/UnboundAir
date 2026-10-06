---
title: Ausgabe-Module
---

# Ausgabe-Module

Wie ein fertiges Dokument vom Dienst zu den Ausgabe-Modulen kommt (AU-02, AU-03, AU-04). Diese Datei beschreibt die allgemeine Schnittstelle und die Kette; was die einzelnen Einstellungen bedeuten, steht in `configuration.md`. v1 bringt genau ein Modul mit, paperless-ngx — dessen Besonderheiten beschreibt der Abschnitt „Das Modul paperless-ngx" weiter unten.

Die Kette ist bewusst schmal: Ein fertiges Dokument wird erst gespeichert und dann übergeben, und der Eintrag wird erst gelöscht, wenn ein Modul die Zustellung bestätigt hat. Dazwischen liegt kein weiterer Zustand — deshalb verliert ein Neustart nichts.

## Die Schnittstelle `OutputModule`

Die Schnittstelle liegt in `output/OutputModule.kt` und ist klein mit Absicht: eine Eigenschaft und eine Methode.

- `name: String` — der Modulname. Er ist die Zeichenkette, die gegen `unboundair.output.modules` abgeglichen wird.
- `fun send(document: OutputDocument)` — liefert das Dokument an den Dienst aus, den das Modul anbindet.

Der auffällige Punkt ist, was `send` **nicht** hat: Es gibt nichts zurück. Zurückkehren heißt „angenommen", Fehlschlag heißt „Ausnahme werfen". Diese Form ist entschieden, nicht dem Modulautor überlassen — der einzige Aufrufer ist die Outbox (AU-04), und ihre einzige Entscheidung lautet „Verzeichnis löschen oder neuen Versuch planen". Dafür genügt ein `try`/`catch` an einer Stelle. Ein Ergebnistyp würde jeden Modulautor zwingen, einen Erfolgswert zu bauen, den niemand liest; ein ignorierter Rückgabewert schlägt lautlos fehl, während eine nicht gefangene Ausnahme nicht übersehen werden kann. Ein Modul, das nicht liefern kann, wirft also; die Outbox behält das Dokument und versucht es erneut.

Die Schnittstelle ist bewusst eine reine Kotlin-Schnittstelle: keine Spring-Annotation, keine injizierten Einstellungen. Ein Modul bekommt seine Einstellungen über den Konstruktor — dasselbe Muster, nach dem der übrige Kern seine Werte übernimmt (das `PageSettings`-Muster).

## Der Dokumenttyp `OutputDocument`

Der Dokumenttyp liegt in `output/OutputDocument.kt`. Er trägt vier Werte:

| Wert | Bedeutung |
|---|---|
| `pdf: Path` | Das gebaute mehrseitige PDF. |
| `pageCount: Int` | Wie viele Seiten es enthält. Absichtlich redundant zum PDF selbst: Ein Modul soll nicht erst das Dokument öffnen müssen, um zu protokollieren, was es gerade liefert. |
| `startedAt: Instant` | Wann die **erste** Seite des Stapels zu scannen begann. Das ist der Zeitstempel des Dokuments durchgehend: Sowohl das `CreationDate` des PDFs als auch der Dateiname `scan-JJJJMMTT-HHMMSS.pdf` (AU-05) leiten sich daraus ab. |
| `finishedAt: Instant` | Wann der Stapel geschlossen wurde. Dient der Diagnose: Die Spanne zu `startedAt` sagt, wie lange das ganze Dokument gedauert hat. |

Es sind dieselben vier Werte, die `service.ScannedDocument` trägt; die Senke des Stapels bildet den einen Typ auf den anderen ab, weil `output` laut Schichtenplan `service` nicht sehen darf — ein paar Zeilen Abbildung sind der billigere Preis.

Die Metadaten reisen **neben** der Datei statt aus dem PDF zurückgelesen zu werden: Die Outbox muss sie ohnehin neben dem Dokument ablegen, damit sie einen Neustart überleben (AU-04), und der Dateiname eines erneut gesendeten Dokuments wird aus `startedAt` neu aufgebaut (AU-05) — die Outbox legt die Datei als `document.pdf` ab, also lässt sich der Name nicht von ihr ablesen.

Der Dateiname selbst (`documentName`) wird aus `startedAt` in der Zeitzone des Containers gebaut; gesteuert wird sie über die übliche Umgebungsvariable `TZ`, nicht über eine eigene Einstellung.

## Die Laufzeit-Auswahl über `OutputModules`

Die Auswahl liegt in `output/OutputModules.kt`. Sie wird einmal beim Start aus allen bekannten Modulen und den Namen aus `unboundair.output.modules` aufgebaut: Nur Module, deren `name` in der konfigurierten Komma-Liste steht, bekommen Dokumente.

Die Auswertung geschieht zur Laufzeit — keine Spring-Annotation entscheidet über die Auswahl, was den Code für ein GraalVM-Native-Image offen hält. Die Details:

- Die Liste ist tolerant im Lesen: Leerzeichen um Einträge werden getrimmt, leere Einträge fallen weg. Eine leere Liste ist gültig und wählt nichts aus — das ist der dokumentierte Default von `unboundair.output.modules` (kein Modul bekommt Dokumente).
- Namen werden exakt verglichen, ohne Umwandlung von Groß- und Kleinschreibung.
- Die Reihenfolge folgt der konfigurierten Liste, nicht der Reihenfolge, in der die Module übergeben wurden.
- Ein unbekannter Name lässt den Dienst gar nicht erst starten: Der Konstruktor wirft eine `IllegalArgumentException`, die den unbekannten Namen und die bekannten Module nennt — ein Tippfehler in `UNBOUNDAIR_OUTPUT_MODULES` darf nicht lautlos alle Uploads abschalten.

Dass `send` Ausnahmen der Module nicht fängt, gehört ebenfalls hierher: Der Vertrag der Schnittstelle lautet „bei Fehlschlag werfen", und nur die Outbox darf über „Verzeichnis löschen oder neuen Versuch planen" entscheiden. Ein Verschlucken an dieser Stelle ließe die Outbox glauben, die Zustellung sei gelungen, und ein nie angekommenes Dokument löschen. Die Ausnahme weiterzureichen hält den Fehler für die Outbox sichtbar — die unscheinbare, aber richtige Wahl.

Die Montage der Kette liegt in `service/OutputPipeline.kt` (`outputPipeline`): Sie prüft unbekannte Namen gegen den Katalog der Namen, die sie bauen kann, baut nur die ausgewählten Module — ein nicht ausgewähltes Modul darf den Start nie aufhalten, etwa wegen eines fehlenden Tokens — und legt dann Outbox und `OutboxRunner` darüber. Der Läufer fragt im Abstand von `unboundair.poll-interval`; eine eigene Outbox-Einstellung dafür gibt es nicht.

## Die Outbox in der Kette

Die Outbox liegt in `output/outbox/Outbox.kt`, der antreibende Läufer in `service/OutboxRunner.kt`. Die Kette läuft so:

1. Das fertige Dokument geht an die Senke der Pipeline (`OutputPipeline.sink`), die es auf `OutputDocument` abbildet und an `Outbox.accept` übergibt.
2. `accept` speichert vollständig — Eintragsverzeichnis, PDF, Metadaten — **bevor** es zurückkehrt. Erst auf diese Ablage hin darf der Aufrufer das Dokument einem Modul übergeben.
3. Der `OutboxRunner` fragt in einer Schleife, was fällig ist (`Outbox.due`), und übergibt jedes fällige Dokument an `OutputModules.send`.
4. Bei sauberer Rückkehr meldet der Läufer Erfolg (`recordSuccess`): Der Eintrag wird aus dem ausstehenden Zustand entfernt und sein Verzeichnis gelöscht — und erst dann. Bei einer Ausnahme meldet er Fehlschlag (`recordFailure`): Der Eintrag bleibt, seine Versuche und sein nächster Versuchstermin werden fortgeschrieben.

Der Kern ist die Reihenfolge: Erst speichern, dann übergeben; erst nach bestätigter Zustellung löschen. Bis dahin ist die Outbox die einzige Kopie. Weil `recover` beim Bau der Outbox dieselben Verzeichnisse wieder einliest — samt fortgeschriebener Versuche und Termine — findet ein Neustart seine ausstehende Arbeit wieder: Nichts, was einmal angenommen war, geht durch einen Absturz verloren. Die Outbox selbst ist passiv angelegt — kein Faden, kein Zeitgeber, keine Kenntnis der Module; sie speichert, benennt Fälliges und hält ihr gemeldete Ergebnisse fest. Die Uhr dreht der Läufer in der Dienstschicht.

Die Wartezeiten stehen als Konstruktor-Parameter an der Outbox, nicht als Einstellungen — sie stimmen einen Algorithmus je Instanz ab, den niemand im Betrieb umstellen muss, weshalb `configuration.md` dafür bewusst keinen Schalter kennt. Die Werte (`Outbox`-Defaults, festgehalten in `backoffDelay`): 30 Sekunden nach dem ersten Fehlversuch, Verdopplung bei jedem weiteren, gedeckelt bei einer Stunde, Versuche unbegrenzt. Mit den Defaults lautet die Folge also 30 s, 1 min, 2 min, … gedeckelt bei 1 h.

## Das Modul paperless-ngx

Das Modul liegt in `output/paperless/PaperlessModule.kt` und lädt das fertige PDF in eine paperless-ngx-Instanz hoch (AU-05, AU-06). Was die einzelnen Einstellungen bedeuten, steht in `configuration.md` unter „Ausgabe"; hier steht, was das Modul mit ihnen tut.

Der Modulname ist `paperless` — genau diese Zeichenkette gehört in `unboundair.output.modules` (Umgebungsvariable `UNBOUNDAIR_OUTPUT_MODULES`), sonst bekommt das Modul keine Dokumente. Die Auswahl vergleicht Namen exakt, und ein unbekannter Name verweigert den Start.

Der Aufruf ist ein POST auf `{baseUrl}/api/documents/post_document/`, wobei `baseUrl` aus `unboundair.output.paperless.base-url` kommt. Die Anmeldung läuft über den Kopf `Authorization: Token ...`. Das PDF reist als Multipart-Teil mit dem Feldnamen `document`.

Der Multipart-Teil trägt den Dateinamen `scan-JJJJMMTT-HHMMSS.pdf`, neu gebaut aus `startedAt` in der Zeitzone des Containers (gesteuert über `TZ`). Der Name wird neu gebaut, weil die Outbox die Datei als `document.pdf` ablegt und der Name ihr also nicht zu entnehmen ist. Der Name ist wichtig, weil paperless den Dokumenttitel daraus ableitet.

`title` und `created` werden bewusst **nicht** gesendet, damit paperless beides selbst aus dem Dateinamen herleitet. Das ist eine feste Planentscheidung, keine Lücke: Es gibt dafür keine Einstellungen, und ein Schalter dafür ist ausdrücklich nicht vorgesehen.

Die optionalen Felder werden nur mitgeschickt, wenn sie konfiguriert sind: `correspondent` aus `unboundair.output.paperless.correspondent` und `document_type` aus `unboundair.output.paperless.document-type` reisen nur mit, wenn ein Wert gesetzt ist; `tags` aus `unboundair.output.paperless.tags` reisen als wiederholte Formularfelder, eines pro Schlagwort-Nummer. Ohne Absender oder Dokumenttyp leitet paperless die Angabe selbst her; ohne Schlagwörter schickt das Modul schlicht keine mit.

Der Token (AU-05) kommt aus `unboundair.output.paperless.token` oder aus der Datei unter `unboundair.output.paperless.token-file` — Umgebungsvariable oder Datei. Ist eine Token-Datei gesetzt, gewinnt sie, auch wenn zusätzlich ein Token gesetzt ist; ist keines von beidem gesetzt, startet der Dienst gar nicht erst. Die Einzelheiten stehen in `configuration.md` unter „Token-Auflösung" und werden hier bewusst nicht wiederholt. Der Token landet nur im Request-Kopf; Logs und Fehlermeldungen nennen Endpunkt und Status, niemals den Token — `PaperlessSettings` maskiert ihn sogar in seiner eigenen Textdarstellung.

Bei Erfolg und Fehlschlag gilt der Vertrag der Schnittstelle (AU-06): Eine 2xx-Antwort liefert die UUID des paperless-Verarbeitungsauftrags, die auf INFO protokolliert wird (`paperless-ngx accepted the document; consumption task ...`), damit man das Dokument in paperless wiederfindet. Jeder andere Status und jeder Transportfehler führen zu einer Ausnahme — die Outbox behält das Dokument und versucht es später erneut.

Ein knappes Beispiel mit Umgebungsvariablen genügt zum Einrichten:

```sh
UNBOUNDAIR_OUTPUT_MODULES=paperless
UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org
UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token
```

Dabei gilt die Schreibregel aus `configuration.md`: Jeder Punkt wird ein Unterstrich, jeder Bindestrich entfällt ersatzlos — also `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL`, nicht `..._BASE_URL`.

## Ein neues Modul schreiben

Das ist die Anleitung, um ein Modul von Grund auf zu bauen. Als nachprüfbares Vorbild dient `RecordingModule` in `src/test/kotlin/dev/digiwomb/unboundair/output/OutputModulesTest.kt` — was hier steht, reicht aus, um so etwas zu schreiben.

**1. Die Schnittstelle implementieren.** Eine Klasse anlegen, die `OutputModule` erfüllt: einen festen `name` und ein `send`, das bei Fehlschlag wirft und bei Erfolg einfach zurückkehrt. Einstellungen kommen über den Konstruktor herein, nicht über Spring-Injektion — das `PageSettings`-Muster:

```kotlin
class ArchiveModule(
    private val endpoint: String,
) : OutputModule {
    override val name: String = "archive"

    override fun send(document: OutputDocument) {
        // Dokument an `endpoint` liefern.
        // Gelingt es nicht, eine Ausnahme werfen —
        // die Outbox behält das Dokument und versucht es erneut.
    }
}
```

Der Name ist der Vertrag mit der Konfiguration: Genau diese Zeichenkette muss später in `unboundair.output.modules` stehen, sonst wählt die Auswahl das Modul nie aus — oder der Start scheitert bei einem Tippfehler lautstark.

**2. Das Modul in `service/OutputPipeline.kt` eintragen, damit die Konfiguration es kennt.** Die Montagefunktion baut die bekannten Module und prüft die konfigurierten Namen gegen ihren Katalog (`KNOWN_MODULE_NAMES`). Ein Modul, das dort nicht gebaut und benannt ist, kann die Auswahl nie erreichen — der Eintrag ist deshalb Teil des Bauens, nicht optional. Dabei gilt die Zurückhaltung, die dort schon für das mitgebrachte Modul gilt: Nur bauen, was unter den konfigurierten Namen ist — ein nicht ausgewähltes Modul darf den Start nie aufhalten.

**3. Konfigurieren und prüfen.** Den Modulnamen in `unboundair.output.modules` aufnehmen (Umgebungsvariable `UNBOUNDAIR_OUTPUT_MODULES`) und den Dienst starten. Ein unbekannter Name verweigert den Start und nennt den Tippfehler sowie die bekannten Module; ein Modul, das nicht in der Liste steht, bekommt schlicht keine Dokumente.
