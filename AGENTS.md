# AGENTS.md – UnboundAir

Dienst, der einen Mustek iScan Air (S400W) in einen „Einlegen und fertig"-Scanner verwandelt: automatisch scannen, verlustfrei zuschneiden, mehrseitige PDFs bauen und an konfigurierbare Ausgabe-Module übergeben (erstes Modul: paperless-ngx). Kotlin + Spring Boot, Betrieb als Container.

**Die Regeln dieses Projekts stehen in `CONTRIBUTING.md`** – Sprache, Commits, Git-Ablauf, Issue-Konvention und Leitplanken. Sie gelten unverändert; diese Datei wiederholt sie nicht, sondern ergänzt sie um die Mechanik einer Arbeitssitzung.

## Zu Beginn jeder Session

1. Diese Datei lesen.
2. `CONTRIBUTING.md` lesen: die Regeln.
3. `docs/internal/plan.md` lesen: Auftrag, feste Entscheidungen, Anforderungen.
4. Offene Arbeit in GitHub nachschlagen: [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) und [Issues](https://github.com/digiwomb-dev/UnboundAir/issues). Beim ersten offenen Issue im aktuellen Meilenstein weitermachen. Gibt es weder Plan noch Issues: nachfragen.

Fachliche Grundlage ist `docs/de/protocol.md` – solange es die noch nicht gibt, `_input/iscan-air-wissen.md`.

## Arbeitsweise – gilt für jede einzelne Antwort

- **Überschaubare Schritte.** Ein Anfänger sollte die Schritte noch verstehen, prüfen und überblicken können.
- **Unklarheiten:** nachfragen statt annehmen.
- **Jede Antwort endet mit genau diesem Block, in dieser Reihenfolge:**
  1. `Das kannst du jetzt sehen:` – konkret prüfbar (Befehl, Dateipfad oder URL). Bei reinen Analyse-Schritten ohne Artefakt ehrlich: „nichts Sichtbares, das war Denkarbeit" – nichts erfinden.
  2. `Nächster Schritt:` – genau EIN Schritt.
- Danach nichts mehr tun und auf mein „Go" warten.

## Planung vor dem Bauen

- **Issues vor jedem Meilenstein:** Bevor du einen Meilenstein baust, legst du die Arbeit als GitHub-Issues im zugehörigen Milestone an: je Aufgabe genau eine Datei, ein prüfbares Abnahmekriterium und die umgesetzten Anforderungs-IDs. Impl+Test-Paare werden als Eltern-Issue mit zwei Sub-Issues angelegt. Gebaut wird erst nach meinem „Go" zu den Issues. Abgehakt (Issue geschlossen) wird erst, wenn eine Aufgabe gebaut **und** abgenommen ist – geschrieben allein genügt nicht.
- Das Titel- und Label-Schema für Issues steht in `CONTRIBUTING.md`.
- **Erst Plan, dann Verhalten:** Soll sich etwas gegenüber `docs/internal/plan.md` ändern, passt du zuerst den Plan an – nach meinem OK – und erst dann den Code.

## Arbeitsregeln für die Sitzung

- **Tests laufen im Dev Container,** nicht direkt auf meinem Rechner. Kannst du sie dort nicht starten: sag es mir – lass sie nicht stillschweigend woanders laufen.
- **Kein Zugriff auf den echten Scanner** ohne meine ausdrückliche Freigabe.
- **Feste Entscheidungen** aus `docs/internal/plan.md` gelten. Willst du davon abweichen: erst fragen.

## Fortschritt

- Fortschritt in GitHub pflegen: Issues abhaken, Milestone schließen, sobald der Meilenstein abgenommen ist.
- Am Ende jedes Meilensteins: Tests im Dev Container grün, kurze Zusammenfassung auf Deutsch (was, warum, offene Punkte), dann den Prüfer aufrufen.

## Prüfer

- Subagent `reviewer`: darf nur lesen und Tests ausführen, ändert nichts.
- Aufruf: am Ende jedes Meilensteins automatisch, sonst wenn ich `@reviewer` schreibe.
- Seinen Befund zeigst du mir unverändert. Behoben wird erst nach meinem „Go", ein Befund pro Schritt.
- Welches Modell er nutzt, lege ich fest – nicht ändern.
