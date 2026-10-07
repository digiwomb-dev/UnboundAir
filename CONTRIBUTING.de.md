# Beiträge zu UnboundAir

Danke für dein Interesse. Diese Datei nennt die Regeln, nach denen hier gearbeitet wird – Sprache, Commits, Git-Ablauf und die Konvention für Issues.

Zwei Dateien gehören daneben:

- **[`docs/de/development.md`](docs/de/development.md)** – wie du das Projekt baust und testest (Dev Container, Build, Testlauf). Diese Datei hier wiederholt davon nichts.
- **[`docs/internal/plan.md`](docs/internal/plan.md)** – der Auftrag: Ziel, feste Entscheidungen und alle Anforderungen mit IDs und Abnahmekriterien. Bei Widersprüchen gilt der Plan.

## Sprache

- **Doku** (`README.md`, `docs/`, diese Datei) wird auf **Deutsch verfasst** – das ist die Quelle, und was du änderst. **Hauptsprache ist Englisch:** Darauf zeigen die Links, und das sieht ein Besucher zuerst.
- **Code, Kommentare, Logs, CLI-Texte, Commit-Messages, Issues und Pull Requests** auf **Englisch**.

Das ist kein Stilgeschmack: Die Doku richtet sich an den Betreiber dieses Scanners, der Code an jeden, der ihn liest.

**Stand seit Meilenstein 6:** Die Doku liegt zweisprachig unter `docs/de/` und `docs/en/` – Deutsch wird verfasst, Englisch wird veröffentlicht (DO-12 bis DO-16 in [`docs/internal/plan.md`](docs/internal/plan.md)).

**Es gelten drei Regeln.** Du änderst immer die deutsche Datei unter `docs/de/`. Die englische Fassung entsteht durch einen Übersetzungslauf, der **lokal auf Abruf** läuft und dessen Entwurf du liest, bevor du ihn committest – CI übersetzt nie, sie prüft nur. Und: `plan.md`, `entscheidungen.md`, `offene-fragen.md` und `teststrategie.md` bleiben deutsch und werden nicht übersetzt; Links darauf tragen `(German only)`.

## Commits und Pull Requests

- **[Conventional Commits](https://www.conventionalcommits.org/)**, kleine Commits pro Schritt.
- **PR-Titel ebenfalls nach Conventional Commits und auf Englisch** – beim Squash-Merge wird er oft zur Commit-Message.
- PR-Beschreibung auf Englisch. Die Vorlage füllt das Nötige vor.
- Der Commit nennt sein Issue (`(#n)` oder `Closes #n`).

## Git-Ablauf

- **`main` ist geschützt.** Direkte Commits sind ausgeschlossen, Änderungen kommen ausschließlich über Pull Requests – das gilt auch für den Inhaber des Repositorys (`enforce_admins`).
- **`dev` ist der Integrations-Branch.** Arbeits-Branches zweigen von `dev` ab und gehen per PR dorthin zurück; `dev` wiederum geht per PR nach `main`. `dev` selbst ist bewusst ungeschützt.
- Pflicht-Reviews sind auf null gesetzt. Der Gewinn des PR liegt hier an der Zusammenfassung und am Diff an einer Stelle, nicht am Häkchen.

Die Begründung dazu steht in [`docs/internal/entscheidungen.md`](docs/internal/entscheidungen.md).

## Issues

Arbeit wird über GitHub-Issues organisiert: je Aufgabe ein Issue. Neue Issues entstehen immer über eine Vorlage aus `.github/ISSUE_TEMPLATE/` – die setzt Issue-Typ und Rolle-Label selbst. Blanko-Issues sind abgeschaltet.

**Du willst nur etwas melden?** Nimm „User report". Dieses Formular verlangt keine Anforderungs-ID – die Zuordnung übernehmen wir. „Defect" ist das Gegenstück für geplante Arbeit an einer bekannten Anforderung.

### Konvention

Jedes Issue ist auf Englisch und trägt seinen GitHub-Issue-Typ plus genau ein `kind/*`-Label. Daran ist filterbar, um welche Art Arbeit es sich handelt.

| Rolle | Typ | Label | Titel |
|---|---|---|---|
| Eltern-Aufgabe | `Task` | `kind/parent` | `task(<scope>): <text> (<IDs>)` |
| Umsetzung | `Feature` | `kind/feat` | `feat(<scope>): <text>` |
| Test | `Test` | `kind/test` | `test(<schicht>): <IDs> <text>` |
| Fehler | `Bug` | `kind/bug` | `fix(<scope>): <text>` |
| Doku | `Task` | `kind/docs` | `docs(<scope>): <text>` |
| Infrastruktur | `Task` | `kind/chore` | `chore(<scope>): <text>`, `spike(<scope>): <text>` |

Dazu:

- **Der Scope ist Pflicht** und benennt das Paket: `config`, `scanner`, `image`, `processing`, `output`, `outbox`, `paperless`, `service`, `batch`, `cli`, `pdf`, `logging`, `app`, `test` — sowie `ci`, `docs`, `repo` und `site` für alles, was kein Paket ist.
- **Bei `test(…)` steht an der Stelle des Scopes die Testschicht** aus [`docs/internal/teststrategie.md`](docs/internal/teststrategie.md), nie das Paket – und dieselbe Schicht zusätzlich als Label. Das Paket steht ohnehin im Dateipfad.
- **Keine `T<n>`-Nummern im Titel.** Die Reihenfolge im Meilenstein steht als `Work order: <n>` im Issue.
- **Ein Eltern-Issue** bekommt nur, wer Sub-Issues hat, und trägt kein Schicht-Label.

Die Labels teilen sich in drei Gruppen, die sich nicht überschneiden:

- **Rolle** (genau eine je Issue): `kind/parent`, `kind/feat`, `kind/test`, `kind/bug`, `kind/docs`, `kind/chore`
- **Testschicht** (nur auf Test-Issues, mehrere möglich): `unit`, `property`, `slice`, `integration`, `contract`, `e2e`, `golden-master`, `mutation`, `guard`
- **Prozess:** `blocked`, `prio-high`

**`blocked` wird wieder abgenommen.** Das Label zeigt an, dass ein **offenes** Issue auf etwas anderes wartet – der Filter heißt `is:open label:blocked`. Sobald die Abhängigkeit steht, kommt es weg, spätestens beim Schließen. Ein geschlossenes Issue trägt nie `blocked`.

### Issues finden

| Was du suchst | Filter |
|---|---|
| alle Eltern-Aufgaben | `label:kind/parent` |
| offene Umsetzungsarbeit | `is:open label:kind/feat` |
| offene Tests im aktuellen Meilenstein | `is:open label:kind/test milestone:5` |
| Tests einer Schicht | `label:kind/test label:integration` |
| offene Fehler | `is:open label:kind/bug` |
| was auf etwas anderes wartet | `is:open label:blocked` |

Auf der Kommandozeile dasselbe über `gh`:

```bash
gh issue list --label kind/parent --milestone 5
gh issue list --label kind/test --label integration --state open
```

### Woran du siehst, was wann dran ist

Ein Filter zeigt, *welche* Arbeit es gibt – nicht, in welcher Folge. Die steht im Issue: `Work order` ist seine Position im Meilenstein, die `Sub-issues`-Zeile listet bei Eltern-Aufgaben die Sub-Issues in Arbeitsreihenfolge (Umsetzung vor dem Test, der sie prüft).

Die Eltern-Aufgaben eines Meilensteins der Reihe nach:

```bash
gh issue list --label kind/parent --milestone 5 --state all --json number,title,body \
  --jq 'map(. + {order: (.body | capture("\\*\\*Work order:\\*\\* (?<w>[0-9]+)").w | tonumber)})
        | sort_by(.order) | .[] | "\(.order)  #\(.number)  \(.title)"'
```

Und für eine einzelne Aufgabe die Sub-Issues in Arbeitsreihenfolge:

```bash
gh issue view 109 --json body --jq '.body | capture("\\*\\*Sub-issues:\\*\\* (?<s>.*)").s'
```

## Tests

Getestet wird im Dev Container, nicht auf dem Rechner daneben – wie das geht, steht in [`docs/de/development.md`](docs/de/development.md). Das Konzept samt Testschichten steht in [`docs/internal/teststrategie.md`](docs/internal/teststrategie.md).

Zwei Regeln, die über den Testlauf hinausgehen:

- **Eine Aufgabe gilt erst als abgenommen, wenn ihr Testergebnis im Issue steht** – Checkliste abgehakt, Lauf im Dev Container grün, Commit-SHA verlinkt. Geschrieben, aber nicht ausgeführt heißt „nicht verifiziert".
- **Tests laufen offline** (DC-03): kein Zugriff auf echte Geräte oder Dienste.

## Leitplanken

Kurzfassung. Maßgeblich sind die Formulierungen in [`docs/internal/plan.md`](docs/internal/plan.md) unter „Feste Entscheidungen" – bei Abweichungen gilt der Plan.

- **Nie neu komprimieren.** Zuschnitt und Graustufen nur per `jpegtran`, JPEGs unverändert ins PDF (OpenPDF, roh als `/DCTDecode`). Zwei benannte Ausnahmen: optionales `normalize` (Default aus) und `bw` (SV-08 – eine 1-bit-Umwandlung kann keine DCT-Transformation sein).
- **Scanner-Antworten per Präfix vergleichen.** Das Gerät hängt Füllbytes an.
- **Ausgabe-Module per Laufzeit-Auswahl,** kein `@ConditionalOnProperty` o. Ä. – das hält GraalVM Native Image offen.
- **Nichts am Protokoll erfinden.** Was offen ist, wird konfigurierbar gebaut und in [`docs/internal/offene-fragen.md`](docs/internal/offene-fragen.md) geführt.
- **Kein Hersteller-Code** im Repository: keine Mustek-Binärdateien, keine Installer.
- **Kein SANE, kein AirScan, kein eSCL.** Keine Web-UI in v1.
- **Keine Secrets im Repository.** Token nur per Umgebungsvariable oder Datei.
- **Kein Zugriff auf den echten Scanner** ohne ausdrückliche Freigabe des Inhabers. Entwickelt und getestet wird gegen den Fake-Scanner.
- **Werkzeug-Konfiguration gehört nicht ins Repository.** Was die lokale Arbeitsumgebung einrichtet, wird nicht mitgeliefert.
- **Feste Entscheidungen aus `docs/internal/plan.md` gelten.** Soll sich etwas daran ändern, wird zuerst der Plan angepasst und erst dann der Code.

## Das Verzeichnis `_input/`

Mehrere Anforderungen im Plan verweisen auf `_input/` – den Wissensstand, Python-Referenzcode und Testbilder. Dieses Verzeichnis liegt nur lokal vor und **wird nie committet** (es steht in `.gitignore`). Wer das Repository klont, hat es nicht.

Inhalte daraus werden gezielt überführt: Wissen nach `docs/`, Testbilder als Test-Ressourcen, Python-Referenzcode in Kotlin **neu geschrieben** – nicht 1:1 übersetzt.

## Sicherheit

Eine Sicherheitslücke gehört nicht in ein öffentliches Issue. Der Meldeweg steht in [`SECURITY.md`](SECURITY.md).
