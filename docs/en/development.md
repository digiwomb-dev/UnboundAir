---
title: Development
---

<!-- translated from docs/de/development.md @ a6a1d8e776a31f45669246f85ae7252e9f3ea826 -->

# Development

How to build and test `UnboundAir`. All work happens in the dev container — the machine itself needs nothing installed beyond a container runtime and dev-container tooling, in particular no JDK and no Gradle.

## Prerequisites

- A container runtime (e.g. Podman or Docker)
- Dev container support: either the "Dev Containers" extension in VS Code or the `devcontainer` CLI
- Git

Nothing more. The container brings the JDK, `jpegtran` and `jbig2`; the wrapper downloads Gradle itself on the first run.

## Starting the dev container

Clone the repository and open it in the dev container. In VS Code: open the folder, then "Reopen in Container". With the CLI:

```bash
devcontainer up --workspace-folder .
```

Without `--workspace-folder` the CLI takes the current directory — inside the repository, `devcontainer up` alone is enough.

With Podman instead of Docker, append `--docker-path podman` — otherwise the CLI looks for an executable named `docker` and aborts with `spawn docker ENOENT`:

```bash
devcontainer up --workspace-folder . --docker-path podman
```

### Working from a git worktree

The repository also builds in the dev container from a [worktree](https://git-scm.com/docs/git-worktree); the folder does not have to be named "UnboundAir". The CLI mounts the working folder at `/workspaces/<folder name>`, and `devcontainer.json` derives `workspaceFolder` from that same name.

One limitation: **git commands only work in the dev container in a plain clone, not in a worktree.** A worktree holds a file instead of a `.git` directory, pointing at the main clone's shared git directory — which sits outside the mounted folder. For building and testing that does not matter: the Gradle build needs no git. Git commands belong beside the container anyway, not inside it.

The `devcontainer` CLI can mount the shared git directory (`--mount-git-worktree-common-dir`), but that requires worktrees created with relative paths (`git worktree add --relative-paths`, since Git 2.48). See OF-12 in `docs/internal/offene-fragen.md`.

The first start builds the image, which takes a few minutes. The output looks partly frozen while it does, because the container runtime's progress display arrives buffered. `--log-level debug` shows each step individually instead.

Then check that the environment is right:

```bash
java -version      # erwartet: Temurin, Version 26
jpegtran -version  # erwartet: eine libjpeg-turbo-Version
jbig2 -V           # erwartet: eine jbig2enc-Version; schreibt auf stderr, Exit 0
```

All three must answer. `jpegtran` is no formality: cropping and grayscale conversion run exclusively through it, and without the program the corresponding tests fail. The same holds for `jbig2`: the 1-bit encoding of the `bw` mode (SV-08) runs exclusively through it, and without the program `Jbig2EncTest`, `PdfBuilderJbig2Test` and `PdfBwGoldenTest` fail.

### Warning when resolving the image name

The base image is pinned by digest in the Dockerfile. The `devcontainer` CLI cannot process the `name:tag@sha256:…` spelling in its pre-check and reports:

```
Path 'library/eclipse-temurin:26-jdk-noble' for input '…@sha256:…' failed validation.
Error fetching image details: Could not parse image name '…'
```

That is inconsequential: the message comes from a metadata query of the CLI, not from the build. The container runtime understands the digest and pulls the image correctly.

## Building and testing

Run everything in the dev container:

```bash
./gradlew build           # kompilieren, Linter, Tests
./gradlew test            # nur Tests
./gradlew spotlessCheck   # nur Linter
./gradlew spotlessApply   # Formatierungsmängel automatisch beheben
```

`spotlessCheck` hangs off the `check` task and runs automatically with `build`. `spotlessApply` changes files — use deliberately, not on the side.

**ktlint** does the checking; Spotless is only the frame that starts it. Why this detour is needed is in `docs/internal/plan.md` under "Entschieden – nicht mehr offen" and in `docs/internal/offene-fragen.md` under OF-11.

The tests get by without real devices and without foreign services: the scanner is replaced by a fake scanner running as a TCP server inside the test, mimicking the real device's behaviour — including quirks like the padding bytes in its answers.

Whoever builds for the first time still needs a network connection: the wrapper downloads the Gradle distribution, Gradle downloads the dependencies. Both land in the cache and are not needed again afterwards.

**The real scanner is never used for tests.** It enters play only with explicit permission and only for measurement runs (`measure`).

## Mutation testing (`pitest`)

Beyond the ordinary tests there is a mutation run: it changes the production code minimally in many places and checks whether the tests notice. That exposes weak assertions a pure line coverage does not show. The layer is described in `docs/internal/teststrategie.md` under "Mutation".

```bash
./gradlew pitest          # Mutationslauf über die Kern-Pakete
```

Four things to know beforehand:

- **Not part of `build`.** The task deliberately hangs off neither `check` nor `build` — it runs only when invoked explicitly. Target: the core packages `scanner`, `image`, `processing`, `output` (with `output.outbox` and `output.paperless`) and `service` — the list in `build.gradle.kts` and `docs/internal/teststrategie.md` rules.
- **It takes a while.** Roughly **2.5 hours** for the full run over all seven packages, because the timed scanner tests rerun for every mutation. A run over a single package (e.g. only `processing`) takes seconds instead. The report lands in `build/reports/pitest/index.html`.
- **The first run needs network.** The `org.pitest` artefacts are not in the normal dependency cache, because only this task uses them. `--offline` therefore fails the first time. That does not touch DC-03: the requirement covers `./gradlew test`, which stays offline.
- **It needs memory.** Gradle daemon, Kotlin daemon and the PIT processes sit in RAM together. On a small container host the Gradle daemon may crash ("daemon disappeared"). When a run aborts, the orphaned PIT main process keeps spawning subprocesses — it then blocks the next run. Clean up first:

  ```bash
  ./gradlew --stop && pkill -f MutationTestMinion; pkill -f pitest-command-line
  ```

There is a **threshold**: when mutation coverage falls below the value pinned in `build.gradle.kts`, the task fails. The value is the last measured state and acts as a floor — it is raised when the score rises, never lowered silently. The current numbers per package are in `docs/internal/entscheidungen.md`.

### Rather run on demand in CI

Because of the 2.5 hours and the memory hunger, the full run does not have to hang off the development machine: the `.github/workflows/pitest.yml` workflow runs it on GitHub Actions. It starts **only by hand** (`workflow_dispatch`), on no `push` — a run costing 2.5 hours on every commit would soon be switched off.

```bash
gh workflow run pitest --ref dev
```

Or pick the `pitest` workflow in the "Actions" tab and name `dev` as the branch. That the workflow file also sits on `main` is no accident: the Actions UI only offers workflows of the default branch for manual start. It should still run on `dev` — the integration branch (see `docs/internal/plan.md`, "Git-Ablauf").

Four points on that:

- **It is the same dev container.** The workflow builds the image from `.devcontainer/Dockerfile` and runs `./gradlew pitest` inside it. CI numbers and local numbers thus come from the same environment — same JDK, `jpegtran` and `jbig2` versions. A runner-side replica would be a second truth about the development environment.
- **Native on both architectures**, like the image check: `ubuntu-24.04-arm` and the standard x86 runner. The golden files are verified against the binaries of both architectures — the CI-01 matrix runs both natively, finding: no difference.
- **The report is the result.** It lives only on the runner, so the workflow uploads `build/reports/pitest/` as the `pitest-report` artefact — kept 90 days, GitHub's maximum. Without that step, 2.5 hours would leave nothing but green or red.
- **One run at a time.** A second start replaces a running one (`concurrency` with `cancel-in-progress`) instead of burning another 2.5 hours in parallel.

Remeasuring the threshold (TE-04) thus belongs in CI, not on the development machine. The measured numbers travel from there to `docs/internal/entscheidungen.md` — with date and commit, as before.

## Continuous integration

What the automation does — and what it expects from you. Details live in the workflow files themselves; this file only describes what to count on.

**On every pull request against `dev`** (`.github/workflows/test.yml`) the full `./gradlew build` runs — suite plus linter — **inside the dev container image**, on both architectures (native `linux/arm64`, native `linux/amd64`). Green on both is required for merging (ruleset on `dev`). The checkout fetches the full history (`fetch-depth: 0`): the translation guard compares commit states, and without history it would call every translation current — whoever copies the workflow as a template leaves that alone.

**Both architectures run the full suite**, because development happens on both: green means the same on either machine. The golden files are verified against the binaries of both architectures.

**Nightly on `dev`** (`.github/workflows/nightly.yml`) runs the same suite plus the image build of both architectures; what was checked there lands in `ghcr.io/digiwomb-dev/unboundair` — `:nightly` as a multi-architecture index, plus `:nightly-arm64` and `:nightly-amd64`. Never `latest`, never a version. Whether the night was green shows the badge in `README.md`.

**Image build and check** (`.github/workflows/image.yml`) build both images and run the CT-01 set inside them (`jpegtran` present, `jbig2` present, `status` reaches the fake scanner) — natively per architecture, never under emulation: under QEMU a build can go green while the real thing fails.

**Cutting a release** (`.github/workflows/release.yml`), in this order: raise `version` in `build.gradle.kts` by pull request to `dev`; merge `dev` into `main`; tag that commit on `main` with exactly that version — strict SemVer, no `v` (`1.2.0`, not `v1.2.0`). A wrong tag, a tag differing from the build, or a tag off `main` fails loudly with reason; the bad tag is deleted by hand, not by the workflow. The release starts as a draft with all assets and goes public last — afterwards it is immutable. `0.y.z` and pre-releases carry the pre-release flag.

**Everything stays runnable locally:** the mutation run still starts on demand, and the guards run before pushing — translation and deployment example in `./gradlew test`, the third-party gate via `npm run check` against `site/dist/`.

## Building the runtime image

The runtime image is the `Dockerfile` in the repository root. It builds from the repository — first produce the jar, because the Dockerfile copies `build/libs/unboundair.jar` into it (no Gradle in the image build):

```bash
./gradlew bootJar
docker build -t <name> .
```

`<name>` is a placeholder: image name and registry are not assigned yet, deliberately no fixed name.

Image acceptance (CT-01) does not run locally but on the GitHub Actions runner `ubuntu-24.04-arm` in `.github/workflows/image.yml`. The reason is plain: an `x86_64` machine without QEMU can neither build nor enter a `linux/arm64` image, so the check happens where native `arm64` hardware exists. The workflow builds the image for `linux/arm64` and runs the three checks inside it (`jpegtran` present, `jbig2` present, `status` reaches the scanner).

Operating the finished image — where it belongs, how it runs — is in `docs/de/operations.md`, not here.

## Fake scanner as a process

The Gradle task `./gradlew fakeScanner` starts the fake scanner as its own process — the same fake scanner the tests otherwise embed as a TCP server in the test process. Useful once you want to try the CLI by hand against a device without touching the real scanner:

```bash
./gradlew fakeScanner
```

The fake scanner listens on port **2323** by default; `-PfakeScannerPort=<n>` changes the port. In a second terminal you can then practise `status` against the fake device:

```bash
java -jar build/libs/unboundair.jar status --host 127.0.0.1 --port 2323
```

(Build the jar first with `./gradlew bootJar`.)

## Gradle wrapper

The wrapper belongs in the repository, including `gradle-wrapper.jar`. The file comes from the official Gradle release; its checksum was verified beforehand against Gradle's published figure:

| | Value |
|---|---|
| Gradle version | 9.7.1 |
| SHA-256 of `gradle-wrapper.jar` | `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |
| Checksum source | `https://services.gradle.org/versions/all`, field `wrapperChecksum` |

When raising the Gradle version, carry this checksum along and verify it again. Once the file exists, it can be rechecked with:

```bash
sha256sum gradle/wrapper/gradle-wrapper.jar
```

## Documentation site

The docs are also available as a rendered site (German and English): https://digiwomb-dev.github.io/UnboundAir/

Build locally (Node stays confined to `site/` — Gradle, dev container and runtime image untouched by it):

```bash
cd site
npm ci --ignore-scripts
npm run build   # Ergebnis in site/dist/
```

The DS-01 gate runs locally with `npm run check` against `site/dist/`. Without the `IMPRINT_BLOCK` and `DONATE_URL` secrets (set only in CI, never in the repository) the imprint assertion fails — like on fork PRs, whose build never deploys.

How the site comes about and what holds for data protection is in `docs/internal/entscheidungen.md` (the sections on the documentation site and on translation).

## Contributing to the repository

Which rules issues follow, how commits and pull requests look and which guardrails apply is in [`CONTRIBUTING.md`](../../CONTRIBUTING.md).

## Why the detour through the dev container

The container holds the same system dependencies as the later runtime image: the same JDK major version, the same `jpegtran`, the same `jbig2`. That is also in the runtime image's `Dockerfile`, and the two files (`.devcontainer/Dockerfile`, `Dockerfile`) belong together: whoever changes one checks the other with it. If the two drift apart, tests go green and the service dies in production. Hence: build and test in the container, not beside it.

### Known quirks

- The `devcontainer` CLI firmly calls `docker`. With Podman, `--docker-path podman` must be given, otherwise it aborts with `spawn docker ENOENT`.
- Long runs look frozen, because progress displays arrive buffered. `--log-level debug` shows the individual steps.
- The digest pin of the base image produces a CLI warning ("Could not parse image name"). Inconsequential, see above.
- When the dev container runs in an environment that knows only one user identity (nested containers without their own UID ranges), every ownership change fails. The `Dockerfile` is set for that: the `apt` download sandbox runs as `root`, and the `chown` on the Gradle directory may fail — unnecessary there anyway, because all files belong to the same identity.

## Joining a work session

Whoever joins here reads in this order:

1. [`CONTRIBUTING.md`](../../CONTRIBUTING.md) – the rules: language, commits, git flow, issue convention, guardrails
2. `docs/internal/plan.md` – mission, firm decisions, requirements with IDs
3. The GitHub milestones and issues – open tasks, what is in progress and what is done
4. This file – building and testing
5. `docs/internal/offene-fragen.md` – what is still unclear about the device

Work continues at the first open issue in the current milestone. A test issue without a green run in the dev container is accepted first — do not keep building and postpone testing.

The `_input/` directory (knowledge base, Python reference code, test images) exists only locally and is not part of the repository. Several requirements refer to it.
