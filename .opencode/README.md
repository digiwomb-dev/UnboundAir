# Lokale KI fuer UnboundAir

`opencode.json` gilt nur fuer dieses Repository. Es verbindet OpenCode mit dem lokalen, OpenAI-kompatiblen Server von LM Studio unter `http://127.0.0.1:1234/v1`; Freigeben von Sitzungen ist abgeschaltet.

## Modelle in LM Studio

Lade diese Modelle nacheinander in LM Studio und aktiviere den lokalen Server:

- `qwen3-coder-30b`: erster Implementierungsversuch.
- `devstral-small`: zweiter, unabhaengiger Implementierungsversuch.
- `gpt-oss-20b`: dritter, unabhaengiger Implementierungsversuch.
- `qwen3-4b`: kleine Hilfsaufgaben von OpenCode.

Die Namen links muessen mit den IDs aus `GET http://127.0.0.1:1234/v1/models` uebereinstimmen. Weichen die von LM Studio gelieferten IDs ab, werden ausschliesslich die Schluessel unter `provider.lmstudio.models` in `opencode.json` und die entsprechenden Agent-Modelle angepasst.

## Eskalation

Nach einem fehlgeschlagenen Hauptversuch wird `@local-second-opinion` verwendet, danach `@local-third-opinion`. Erst nach drei erfolglosen lokalen Modellen darf ein Cloud-Fallback vorgeschlagen werden. Er braucht jedes Mal deine ausdrueckliche Freigabe.

`@cloud-fallback` ist absichtlich deaktiviert. Sobald Anbieter und Modell feststehen, wird er mit einem Modell eingerichtet, das von der Hauptsession abweicht.

OpenCode liest Konfiguration, Agents und Skills nur beim Start. Nach jeder Aenderung in `.opencode/` oder an `opencode.json` OpenCode beenden und neu starten.
