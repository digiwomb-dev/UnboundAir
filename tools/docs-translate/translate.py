#!/usr/bin/env python3
"""Translate one German documentation file into English with a local model (DO-14).

Flow:
    1. you edit        docs/de/<file>.md and commit it
    2. you run         tools/docs-translate/translate.py docs/de/<file>.md
    3. it writes      docs/en/<file>.md plus the provenance marker in line 1
    4. the guard runs  TranslationGuardTest, structural check, no LLM
    5. you read       the draft, then commit it
    6. CI             checks, builds, publishes

Commit the German file first: the marker names the commit the translation was
made from, so translating uncommitted text guarantees a stale marker the
moment both land. The script warns when the source is dirty and proceeds
anyway — the draft is read by a human regardless.

Configuration comes only from the environment; nothing is hardcoded, so
anyone without LM Studio can point the script elsewhere or translate by hand:

    UNBOUNDAIR_DOCS_LLM_URL    base URL of an OpenAI-compatible endpoint
                               (e.g. http://localhost:1234/v1)
    UNBOUNDAIR_DOCS_LLM_MODEL  model name (first model: Gemma 4 26B,
                               named fallback: Qwen3.6 27B)

Never part of any build and never run in CI: a cloud runner cannot reach a
local model, and the draft is always read before it is committed.

Usage:
    tools/docs-translate/translate.py docs/de/operations.md [--force]
"""

import json
import os
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def fail(message):
    print(f"translate: error: {message}", file=sys.stderr)
    sys.exit(1)


def repo_run(*args):
    process = subprocess.run(
        ["git", *args], cwd=repo_root(), capture_output=True, text=True, timeout=60
    )
    if process.returncode != 0:
        fail(f"git {' '.join(args)} failed: {process.stderr.strip()}")
    return process.stdout.strip()


def repo_root():
    root = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if root.returncode != 0:
        fail("not inside a git working tree")
    return root.stdout.strip()


def read_glossary():
    terms = []
    with open(os.path.join(HERE, "GLOSSARY.md"), encoding="utf-8") as handle:
        for line in handle:
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            if len(cells) != 2 or cells[0].lower() == "german" or set(cells[1]) == {"-"}:
                continue
            terms.append(f"| {cells[0]} | {cells[1]} |")
    if not terms:
        fail("GLOSSARY.md holds no terms")
    return "| German | English |\n|---|---|\n" + "\n".join(terms)


def build_prompt(document):
    with open(os.path.join(HERE, "PROMPT.md"), encoding="utf-8") as handle:
        template = handle.read()
    return template.replace("{glossary}", read_glossary()).replace("{document}", document)


def translate(base_url, model, prompt):
    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0,
            "stream": False,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=1200) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception as error:
        fail(f"request to {base_url} failed: {error}")
    try:
        content = payload["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        fail(f"unexpected reply shape: {json.dumps(payload)[:300]}")
    text = content.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines[-1].strip() == "```":
            text = "\n".join(lines[1:-1]).strip() + "\n"
    return text


def main(argv):
    force = "--force" in argv
    sources = [arg for arg in argv[1:] if not arg.startswith("-")]
    if len(sources) != 1:
        fail("usage: translate.py docs/de/<file>.md [--force]")
    root = repo_root()
    source = os.path.normpath(os.path.join(os.getcwd(), sources[0]))
    if not source.startswith(root + os.sep) or "/docs/de/" not in source.replace(os.sep, "/"):
        fail("source must be a file below docs/de/")
    name = os.path.basename(source)
    with open(source, encoding="utf-8") as handle:
        document = handle.read()
    base_url = os.environ.get("UNBOUNDAIR_DOCS_LLM_URL", "").strip()
    model = os.environ.get("UNBOUNDAIR_DOCS_LLM_MODEL", "").strip()
    if not base_url or not model:
        fail(
            "set UNBOUNDAIR_DOCS_LLM_URL and UNBOUNDAIR_DOCS_LLM_MODEL "
            "(e.g. http://localhost:1234/v1 and the Gemma 4 26B model name)"
        )
    dirty = subprocess.run(
        ["git", "status", "--porcelain", "--", os.path.relpath(source, root)],
        cwd=root,
        capture_output=True,
        text=True,
        timeout=60,
    ).stdout.strip()
    if dirty:
        print(
            "translate: warning: the German source has uncommitted changes; "
            "commit it first or the marker will lag behind the text.",
            file=sys.stderr,
        )
    target = os.path.join(root, "docs", "en", name)
    if os.path.exists(target) and not force:
        fail(f"{os.path.relpath(target, root)} exists already, pass --force to overwrite it")
    print(f"translate: {model} @ {base_url} -> {os.path.relpath(target, root)} ...", flush=True)
    english = translate(base_url, model, build_prompt(document))
    commit = repo_run("rev-parse", "HEAD")
    german_rel = os.path.relpath(source, root).replace(os.sep, "/")
    marker = f"<!-- translated from {german_rel} @ {commit} -->\n"
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(marker + english)
    print("translate: draft written; read it before committing it.")


if __name__ == "__main__":
    main(sys.argv)
