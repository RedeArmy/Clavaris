# Security Policy

Clavaris is a self-hosted identity provider — the credential store and token
issuer for everything that depends on it. A vulnerability here has a blast
radius across every consuming application, not just this repository. If you
find one, please report it privately.

## Reporting a vulnerability

**Do not open a public GitHub issue for a security vulnerability.**

Report it via **GitHub's private vulnerability reporting**:
[Security → Report a vulnerability](https://github.com/RedeArmy/Clavaris/security/advisories/new)
on this repository. This opens a private advisory visible only to the
maintainer until a fix is ready — nothing is disclosed publicly until then.

Please include:

- The affected component (module, endpoint, or flow) and, if possible, a
  minimal reproduction.
- The potential impact as you understand it (e.g. account takeover,
  cross-tenant data access, token forgery) — `docs/04-security/`
  (`threat-model-stride.md`, `security-architecture.md`) describes the
  system's own threat model and may help frame it.
- Whether you're aware of it being exploited in the wild.

## Current state

This project is pre-launch: no consumer sends real user traffic through it
yet, and a mandatory external security review is required before any
consumer does — non-negotiable. Reports made now, before that review, are
still valuable and still handled seriously.

One known gap, tracked openly rather than hidden: there is no formal
incident-response runbook yet for compromise of the `PlatformClient`
bootstrap credential — the single highest-value target in the system,
since it can create and thus reach every tenant. If your report relates
to this credential specifically, please still report it privately; treat
it as high-severity by default.

## Supported versions

Pre-v1, trunk-based (`docs/05-engineering/git-workflow.md` §1) — there is
one supported line: the tip of `master`. There are no older maintained
releases to report against separately.
