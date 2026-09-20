---
description: Reserved placeholder for a manually approved cloud fallback after three different local models failed.
mode: subagent
disable: true
---

This agent is intentionally disabled until a cloud provider and model are chosen.

It may be enabled only after unsuccessful attempts with local-primary, devstral-small, and gpt-oss-20b. A primary agent must first report the three attempts, their model names, approaches, and concrete blockers, then obtain explicit user approval. The selected cloud model must differ from the primary session model.

Never send secrets, tokens, files from _input/, or scanner raw data to a cloud model.
