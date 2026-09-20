# Lokale KI fuer UnboundAir

`opencode.json` gilt nur fuer dieses Repository und schaltet das Freigeben von Sitzungen ab. Die Verbindung zu LM Studio und die Modellregistrierung verwaltet das installierte LM-Studio-Plugin.

## Modelle in LM Studio

Waehle fuer `@local-primary` je Session selbst ein lokales Modell. Die weiteren Versuche nutzen diese voneinander abweichenden Modelle:

- `devstral-small`: zweiter, unabhaengiger Implementierungsversuch.
- `gpt-oss-20b`: dritter, unabhaengiger Implementierungsversuch.

Die Modell-IDs muessen den vom LM-Studio-Plugin bereitgestellten IDs entsprechen. Weichen sie ab, werden nur die `model`-Werte der beiden alternativen Agent-Dateien angepasst.

## Eskalation

`@local-primary` fuehrt den ersten lokalen Versuch mit dem von dir fuer die Session gewaehlten Modell aus. Nach einem fehlgeschlagenen Hauptversuch wird `@local-second-opinion` verwendet, danach `@local-third-opinion`. Erst nach drei erfolglosen lokalen Modellen darf ein Cloud-Fallback vorgeschlagen werden. Er braucht jedes Mal deine ausdrueckliche Freigabe.

`@cloud-fallback` ist absichtlich deaktiviert. Sobald Anbieter und Modell feststehen, wird er mit einem Modell eingerichtet, das von der Hauptsession abweicht.

OpenCode liest Konfiguration, Agents und Skills nur beim Start. Nach jeder Aenderung in `.opencode/` oder an `opencode.json` OpenCode beenden und neu starten.
