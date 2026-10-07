# Contributing to UnboundAir

Thank you for your interest. This file names the rules for working here – language, commits, git flow and the convention for issues.

Two files belong next to it:

- **[`docs/en/development.md`](docs/en/development.md)** – how to build and test the project (dev container, build, test run). Nothing in here repeats that.
- **[`docs/internal/plan.md`](docs/internal/plan.md)** (German only) – the mission: goal, firm decisions and all requirements with IDs and acceptance criteria. When in doubt, the plan rules.

## Language

- **Docs** (`README.md`, `docs/`, this file) are **written in German** – that is the source, and what you change. **English is the main language:** links point there, and visitors see it first.
- **Code, comments, logs, CLI texts, commit messages, issues and pull requests** in **English**.

That is not a matter of taste: the docs address this scanner's operator, the code addresses everyone reading it.

**State since milestone 6:** docs live bilingually under `docs/de/` and `docs/en/` – German is written, English is published (DO-12 to DO-16 in [`docs/internal/plan.md`](docs/internal/plan.md) (German only)).

**Three rules apply.** You always change the German file under `docs/de/`. The English version comes from a translation run that executes **locally on demand** and whose draft you read before committing it – CI never translates, it only checks. And: `plan.md`, `entscheidungen.md`, `offene-fragen.md` and `teststrategie.md` stay German and are never translated; links to them carry `(German only)`.

## Commits and pull requests

- **[Conventional Commits](https://www.conventionalcommits.org/)**, small commits per step.
- **PR titles also follow Conventional Commits and in English** – on squash-merge they often become the commit message.
- PR description in English. The template pre-fills what is needed.
- The commit names its issue (`(#n)` or `Closes #n`).

## Git flow

- **`main` is protected.** Direct commits are excluded, changes arrive exclusively through pull requests – including for the repository owner (`enforce_admins`).
- **`dev` is the integration branch.** Work branches fork from `dev` and return to it through PRs; `dev` in turn goes to `main` through PRs. Direct commits to `dev` are possible for the owner (ruleset bypass; local acceptance of every step stays mandatory), but every PR must pass the CI-01 checks — a ruleset on `dev` requires both runs (`arm64`, `amd64`) as status checks, nothing more.
- Required reviews are set to zero. The PR's value lies in the summary and the diff in one place, not in the checkmark.

The reasoning is in [`docs/internal/entscheidungen.md`](docs/internal/entscheidungen.md) (German only).

## Issues

Work is organised through GitHub issues: one issue per task. New issues always come from a template in `.github/ISSUE_TEMPLATE/` – it sets issue type and role label itself. Blank issues are disabled.

**Just reporting something?** Take "User report". That form requires no requirement ID – we do the mapping. "Defect" is its counterpart for planned work on a known requirement.

### Convention

Every issue is in English and carries its GitHub issue type plus exactly one `kind/*` label. That is what you filter by for the kind of work.

| Role | Type | Label | Title |
|---|---|---|---|
| Parent task | `Task` | `kind/parent` | `task(<scope>): <text> (<IDs>)` |
| Implementation | `Feature` | `kind/feat` | `feat(<scope>): <text>` |
| Test | `Test` | `kind/test` | `test(<layer>): <IDs> <text>` |
| Defect | `Bug` | `kind/bug` | `fix(<scope>): <text>` |
| Docs | `Task` | `kind/docs` | `docs(<scope>): <text>` |
| Infrastructure | `Task` | `kind/chore` | `chore(<scope>): <text>`, `spike(<scope>): <text>` |

On top:

- **The scope is required** and names the package: `config`, `scanner`, `image`, `processing`, `output`, `outbox`, `paperless`, `service`, `batch`, `cli`, `pdf`, `logging`, `app`, `test` — plus `ci`, `docs`, `repo` and `site` for everything that is no package.
- **For `test(…)` the scope slot takes the test layer** from [`docs/internal/teststrategie.md`](docs/internal/teststrategie.md) (German only), never the package – and the same layer additionally as a label. The package is in the file path anyway.
- **No `T<n>` numbers in titles.** The order in the milestone stands as `Work order: <n>` in the issue.
- **A parent issue** goes only to whoever has sub-issues, and carries no layer label.

Labels fall into three groups that do not overlap:

- **Role** (exactly one per issue): `kind/parent`, `kind/feat`, `kind/test`, `kind/bug`, `kind/docs`, `kind/chore`
- **Test layer** (test issues only, several possible): `unit`, `property`, `slice`, `integration`, `contract`, `e2e`, `golden-master`, `mutation`, `guard`
- **Process:** `blocked`, `prio-high`

**`blocked` is removed again.** The label shows that an **open** issue waits on something else – the filter reads `is:open label:blocked`. Once the dependency lands it goes away, at the latest on closing. A closed issue never carries `blocked`.

### Finding issues

| What you look for | Filter |
|---|---|
| all parent tasks | `label:kind/parent` |
| open implementation work | `is:open label:kind/feat` |
| open tests in the current milestone | `is:open label:kind/test milestone:5` |
| tests of one layer | `label:kind/test label:integration` |
| open defects | `is:open label:kind/bug` |
| what waits on something else | `is:open label:blocked` |

On the command line the same through `gh`:

```bash
gh issue list --label kind/parent --milestone 5
gh issue list --label kind/test --label integration --state open
```

### Seeing what is due when

A filter shows *which* work exists – not in which order. That order is in the issue: `Work order` is its position in the milestone, the `Sub-issues` line lists sub-issues of parent tasks in working order (implementation before the test that checks it).

The parent tasks of a milestone in order:

```bash
gh issue list --label kind/parent --milestone 5 --state all --json number,title,body \
  --jq 'map(. + {order: (.body | capture("\\*\\*Work order:\\*\\* (?<w>[0-9]+)").w | tonumber)})
        | sort_by(.order) | .[] | "\(.order)  #\(.number)  \(.title)"'
```

And for a single task the sub-issues in working order:

```bash
gh issue view 109 --json body --jq '.body | capture("\\*\\*Sub-issues:\\*\\* (?<s>.*)").s'
```

## Tests

Testing happens in the dev container, not on the machine next to it – how is in [`docs/en/development.md`](docs/en/development.md). The concept with test layers is in [`docs/internal/teststrategie.md`](docs/internal/teststrategie.md) (German only).

Two rules beyond the test run:

- **A task counts as accepted only once its test result is in the issue** – checklist ticked, run in the dev container green, commit SHA linked. Written but not executed means "not verified".
- **Tests run offline** (DC-03): no access to real devices or services.

## Guardrails

Short version. The formulations in [`docs/internal/plan.md`](docs/internal/plan.md) (German only) under "Feste Entscheidungen" rule – when in doubt, the plan wins.

- **Never recompress.** Cropping and grayscale only through `jpegtran`, JPEGs unchanged into the PDF (OpenPDF, raw as `/DCTDecode`). Two named exceptions: optional `normalize` (default off) and `bw` (SV-08 – a 1-bit conversion cannot be a DCT transform).
- **Compare scanner answers by prefix.** The device appends padding bytes.
- **Output modules by runtime selection,** no `@ConditionalOnProperty` or similar – that keeps GraalVM native image open.
- **Invent nothing about the protocol.** What is open is built configurable and kept in [`docs/internal/offene-fragen.md`](docs/internal/offene-fragen.md) (German only).
- **No manufacturer code** in the repository: no Mustek binaries, no installers.
- **No SANE, no AirScan, no eSCL.** No web UI in v1.
- **No secrets in the repository.** Tokens only through environment variable or file.
- **No access to the real scanner** without the owner's explicit permission. Development and testing run against the fake scanner.
- **Tooling configuration does not belong in the repository.** What sets up the local working environment is not shipped.
- **Firm decisions from `docs/internal/plan.md` (German only) apply.** When something is to change about them, first adjust the plan – after my OK – and only then the code.

## The `_input/` directory

Several requirements in the plan refer to `_input/` – the knowledge base, Python reference code, test images. That directory exists only locally and **is never committed** (it is in `.gitignore`). Whoever clones the repository does not have it.

Contents move across deliberately: knowledge to `docs/`, test images to test resources, Python reference code rewritten in Kotlin – not translated 1:1.

## Security

A security hole does not belong in a public issue. The reporting route is in [`SECURITY.md`](SECURITY.md).
