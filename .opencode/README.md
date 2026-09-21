# Lokale KI für UnboundAir

`opencode.json` gilt nur für dieses Repository und schaltet das Freigeben von Sitzungen ab. Die Verbindung zu LM Studio und die Modellregistrierung verwaltet das installierte LM-Studio-Plugin.

## Modelle in LM Studio

Starte die Hauptsession mit dem Modell deiner Wahl; es gilt für die Session selbst und `@reviewer`. `@local-primary` nutzt fest `qwen3.8-27b`. Die weiteren Versuche nutzen diese voneinander abweichenden Modelle:

- `qwen3-coder-30b`: zweiter, unabhängiger Implementierungsversuch.
- `devstral-small-2-2512`: dritter, unabhängiger Implementierungsversuch.

Die Modell-IDs müssen den vom LM-Studio-Plugin bereitgestellten IDs entsprechen. Weichen sie ab, werden die `model`-Werte der Agent-Dateien entsprechend angepasst.

## Eskalation

Rufe `@local-primary` in der Hauptsession auf; er nutzt fest `qwen3.8-27b`. Nach einem fehlgeschlagenen ersten Versuch folgt `@local-second-opinion`, danach `@local-third-opinion`. Erst nach drei erfolglosen lokalen Modellen darf ein Cloud-Fallback vorgeschlagen werden. Er braucht jedes Mal deine ausdrückliche Freigabe.

`@cloud-fallback` ist absichtlich deaktiviert. Sobald Anbieter und Modell feststehen, wird er mit einem Modell eingerichtet, das von den drei lokalen Modellen abweicht.

OpenCode liest Konfiguration, Agents und Skills nur beim Start. Nach jeder Änderung in `.opencode/` oder an `opencode.json` OpenCode beenden und neu starten.
