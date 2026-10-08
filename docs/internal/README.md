# Arbeitsunterlagen

Die internen Unterlagen des Projekts: warum es gebaut wird, wie entschieden wurde, was am Gerät offen ist und wie geprüft wird.

**Nur auf Deutsch.** Die Produktdoku unter `docs/de/` und `docs/en/` ist zweisprachig, diese vier Dateien sind es ausdrücklich nicht (DO-12): Der Auftrag ist das Original, und eine zweite Fassung müsste bei jeder Planänderung mitgezogen werden, bevor überhaupt Code entstehen darf. Englische Seiten, die hierher verlinken, schreiben deshalb `(German only)` an den Link – ein Wächter prüft das.

**Nicht auf der Doku-Seite.** Die [Seite](https://digiwomb-dev.github.io/UnboundAir/) baut ausschließlich aus `docs/de/` und `docs/en/` (DO-13). Dieses Verzeichnis ist keine Inhaltsquelle, also tragen die Dateien hier auch kein Frontmatter. Gelesen werden sie im Repository.

## Die vier Dateien

- **[`plan.md`](plan.md)** – der Auftrag: Ziel, feste Entscheidungen und der Anforderungskatalog. Jede Anforderung trägt eine ID (`SC-01`, `DO-20`, …) und ihr eigenes Abnahmekriterium; Commits und Issues verweisen auf diese IDs. Hier stehen auch die gepinnten Versionen und die erlaubten Paket-Abhängigkeiten. Bei Widersprüchen gilt diese Datei.
- **[`entscheidungen.md`](entscheidungen.md)** – das Logbuch: zu jeder festen Entscheidung die Begründung, meist samt der verworfenen Alternative und den gemessenen Zahlen. Es wird fortgeschrieben, nicht umgeschrieben – überholte Einträge bleiben lesbar und werden als überholt markiert, denn ein Log, das sich selbst überschreibt, hört auf, ein Log zu sein.
- **[`offene-fragen.md`](offene-fragen.md)** – was am Gerät oder am Protokoll ungeklärt ist, je mit Status, Herkunft und der Messung, die es klären würde. Grundlage der Leitplanke „nichts am Protokoll erfinden": Was nicht gemessen ist, wird konfigurierbar gebaut und hier geführt, statt geraten zu werden.
- **[`teststrategie.md`](teststrategie.md)** – die Testschichten von Unit bis Mutation, je mit Werkzeug und Begründung, dazu die bekannten Grenzen der Wächter. Eine Schicht ist, was in `./gradlew test` läuft; alles, was ein gebautes Artefakt oder echte Hardware braucht, ist ein CI-Tor und keine Schicht.

Wie gearbeitet wird, steht nicht hier, sondern in [`CONTRIBUTING.md`](../../CONTRIBUTING.md); der Fortschritt lebt in den [Issues](https://github.com/digiwomb-dev/UnboundAir/issues) und [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones).
