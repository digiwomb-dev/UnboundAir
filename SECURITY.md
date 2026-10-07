# Security

## Reporting a hole

**Please not through a public issue.** Use GitHub's private reporting route instead:

**[Report a security hole](https://github.com/digiwomb-dev/UnboundAir/security/advisories/new)**

The report is visible only to the project's maintainers until a fix exists. Helpful is what you observed, how to trigger it and which state is affected (commit or image tag).

What to expect, honestly: this is one person's hobby project. An answer may take a few days. I would rather state that truth than a deadline I cannot keep.

Anything that is no security hole – a defect, a malfunction – belongs in an [issue](https://github.com/digiwomb-dev/UnboundAir/issues/new/choose).

## Which versions are covered

v1 is not released yet. Covered is therefore exactly the **current state of `main`**; there is no backporting into older states. Once releases exist, a table stands here.

## Tokens and secrets

**No token belongs in the repository.** The paperless API token arrives at runtime – either as an environment variable (`UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN`) or, preferably, as a mounted file whose path is announced through `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE`. The service names the token in no log and no error message; `PaperlessSettings` masks it even in its own string representation.

The details – which source wins, why the service does not start at all without either – are under "Token resolution" in [`docs/en/configuration.md`](docs/en/configuration.md); how the secret reaches the container is in [`docs/en/operations.md`](docs/en/operations.md).

When you accidentally published a token in a log, an issue or a commit: revoke it in paperless-ngx and create a new one. It cannot reliably be removed from git history.

## Scope

**Included:** the service itself, the container image and the configuration – in particular everything writing files (outbox, PDF generation) or handling credentials.

**Not included:** the scanner's firmware and protocol. The device opens its own WLAN whose WPA password is fixed by the manufacturer and published in the manual, and speaks plain text in it over TCP port 23 – without authentication and without encryption. Both are properties of the device this project cannot fix; it can only talk to them. Whoever stands within radio reach of the scanner can talk to it – which is why [`docs/en/operations.md`](docs/en/operations.md) describes a packet filter allowing the foreign network only what it needs, and a network profile that does not bend the host's default route. A report about the device itself is no finding in UnboundAir.

Also outside: paperless-ngx itself and the container runtime. Both have their own reporting routes.
