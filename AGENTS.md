# AGENTS.md – UnboundAir

Dienst, der einen Mustek iScan Air (S400W) in einen „Einlegen und fertig"-Scanner verwandelt: automatisch scannen, verlustfrei zuschneiden, mehrseitige PDFs bauen und an konfigurierbare Ausgabe-Module übergeben (erstes Modul: paperless-ngx). Kotlin + Spring Boot, Betrieb als Container.

## Zu Beginn jeder Session

1. Diese Datei lesen.
2. `docs/plan.md` lesen: Auftrag, feste Entscheidungen, Meilensteine mit Status.
3. Beim ersten offenen Punkt im Plan weitermachen. Gibt es den Plan noch nicht: nachfragen.

Fachliche Grundlage ist `docs/protokoll.md` – solange es die noch nicht gibt, `_input/iscan-air-wissen.md`.

## Arbeitsweise – gilt für jede einzelne Antwort

- **Überschaubare Schritte.** Ein Anfänger sollte die Schritte noch verstehen, prüfen und überblicken können.
- **Unklarheiten:** nachfragen statt annehmen.
- **Jede Antwort endet mit genau diesem Block, in dieser Reihenfolge:**
  1. `Das kannst du jetzt sehen:` – konkret prüfbar (Befehl, Dateipfad oder URL). Bei reinen Analyse-Schritten ohne Artefakt ehrlich: „nichts Sichtbares, das war Denkarbeit" – nichts erfinden.
  2. `Nächster Schritt:` – genau EIN Schritt.
- Danach nichts mehr tun und auf mein „Go" warten.

## Arbeitsregeln

- **Sprache:** Doku (README, `docs/`) primär auf Deutsch; Deutsch ist die führende Fassung. Eine englische Übersetzung kommt später ins Repo – wie, ist noch offen, bis dahin nur Deutsch schreiben. Code, Kommentare, Logs, CLI-Texte und Commit-Messages auf Englisch.
- **Commits und PRs:** Conventional Commits, kleine Commits pro Schritt. PR-Titel ebenfalls nach Conventional Commits und auf Englisch, weil der PR-Titel beim Squash-Merge oft zur Commit-Message wird. PR-Beschreibung auf Englisch.
- **`_input/` nie committen.** Inhalte gezielt überführen: Wissen → `docs/`, Testbild → Test-Ressourcen, Python-Referenzcode in Kotlin neu schreiben (nicht 1:1 übersetzen).
- **Kein Hersteller-Code**, keine Mustek-Binärdateien oder Installer im Repo.
- **Kein Zugriff auf den echten Scanner** ohne meine ausdrückliche Freigabe. Entwickelt und getestet wird gegen den Fake-Scanner.
- **Tests laufen im Dev Container,** nicht direkt auf meinem Rechner.
- **Keine Secrets im Repo.** Token nur per Umgebungsvariable oder Datei.
- **Feste Entscheidungen** aus `docs/plan.md` gelten. Willst du davon abweichen: erst fragen.

## Leitplanken – nie verletzen

Kurzfassung. Maßgeblich sind die ausführlichen Formulierungen in `docs/plan.md` unter „Feste Entscheidungen" – bei Abweichungen gilt der Plan.

- **Nie neu komprimieren:** Zuschnitt und Graustufen nur per `jpegtran`, JPEGs unverändert ins PDF (PDFBox `JPEGFactory`). Einzige Ausnahme: optionales `normalize`, Default aus.
- **Scanner-Antworten per Präfix vergleichen** – das Gerät hängt Füllbytes an.
- **Ausgabe-Module per Laufzeit-Auswahl,** kein `@ConditionalOnProperty` o. Ä. (hält GraalVM Native Image offen).
- **Nichts am Protokoll erfinden.** Was offen ist, konfigurierbar bauen und in `docs/offene-fragen.md` führen.
- Kein SANE, kein AirScan, kein eSCL. Keine Web-UI in v1.

## Planung vor dem Bauen

- **Aufgabenliste vor jedem Meilenstein:** Bevor du einen Meilenstein baust, legst du dafür eine eigene Datei `docs/meilenstein-N.md` an: Aufgaben (je eine ID wie `T1.1`, genau eine Datei, ein prüfbares Abnahmekriterium, die umgesetzten Anforderungs-IDs), Testpunkte und Teststand. `docs/plan.md` verweist nur darauf. Gebaut wird erst nach meinem „Go" zur Liste. Abgehakt wird erst, wenn eine Aufgabe gebaut **und** abgenommen ist – geschrieben allein genügt nicht.
- **Erst Plan, dann Verhalten:** Soll sich etwas gegenüber `docs/plan.md` ändern, passt du zuerst den Plan an – nach meinem OK – und erst dann den Code.

## Fortschritt

- Status der Meilensteine in `docs/plan.md` aktuell halten, spätestens am Ende jedes Meilensteins.
- Am Ende jedes Meilensteins: Tests im Dev Container grün, kurze Zusammenfassung auf Deutsch (was, warum, offene Punkte), dann den Prüfer aufrufen.

## Prüfer

- Subagent `pruefer`: darf nur lesen und Tests ausführen, ändert nichts.
- Aufruf: am Ende jedes Meilensteins automatisch, sonst wenn ich `@pruefer` schreibe.
- Seinen Befund zeigst du mir unverändert. Behoben wird erst nach meinem „Go", ein Befund pro Schritt.
- Welches Modell er nutzt, lege ich in seiner Agent-Datei fest – nicht ändern.
