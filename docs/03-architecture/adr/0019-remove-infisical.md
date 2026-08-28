# ADR-0019: Remove Infisical — supersedes ADR-0014's own Phase 2

**Status:** ✅ Aprobado (2026-08-28)

## Context

ADR-0014 built self-hosted Infisical as **Phase 2** of a two-phase secrets-management plan, in
dual mode: `docker-entrypoint.sh` authenticates and pulls secrets from Infisical when
`INFISICAL_CLIENT_ID`/`SECRET` are set, and falls back to today's plaintext `.env`/process-env-var
behavior otherwise — a deliberate migration path, never a hard cutover, so nothing about the
existing local-dev flow would break.

ADR-0018 (same day, 2026-08-28) then made the actual production-deployment decision this Phase 2
was building toward: for the real, single-VM `docker-compose.prod.yml` artifact, **`.env` is the
primary path, not Infisical** — running Infisical's own three-service stack (`infisical-db`,
`infisical-redis`, `infisical`) on the same single VM this deployment is deliberately kept simple
would roughly double its service count to protect secrets already root-only-readable on that same
host. That decision leaves Infisical support in a real, awkward state worth naming honestly: fully
built, dual-mode, zero risk to the existing flow — **and never actually the path any real
deployment this project has takes.**

Asked directly whether that state is still worth keeping. Audited every real, concrete cost before
answering, not just the abstract "unused code" instinct:

- **The Infisical CLI is installed unconditionally in every image `app/Dockerfile` builds** —
  regardless of whether Infisical mode is ever exercised. That install is also the direct cause of
  `TD-SEC-024`: 7 HIGH-severity Go-stdlib CVEs baked into that exact binary at Infisical's own
  upstream build time, currently risk-accepted via `.trivyignore` with an open "revisit the day a
  patched build ships" trigger this project has to keep tracking. Removing Infisical removes those
  7 accepted CVEs and that open tracking burden entirely, not just narrows them.
- **`roadmap-and-release-plan.md` already independently flagged the cost category this sits in**
  (§13, "Operational surface for one person keeps growing" — four Compose files, Infisical one of
  three optional overlays) as worth watching for a project whose own `nfr-quality-attributes.md`
  §6 names solo-developer operability as a hard constraint. This wasn't written to justify removing
  Infisical, but it's the same finding from a different angle, found before this question was asked.
  Removing this overlay directly shrinks that named, already-tracked risk.
- **Real, ongoing maintenance surface for a path that has never once been exercised in a real
  environment**: `docker-compose.infisical.yml` (3 services), `docs/05-engineering/
  infisical-setup.md` (a full bootstrap runbook), `docker-entrypoint.sh`'s own dual-mode branch,
  and mentions across `risk-register.md`/`vendor-management.md`/`asset-inventory.md`/
  `access-review.md` treating Infisical's own machine identity as one of this project's
  highest-value credentials requiring its own access review — a real governance surface for a
  credential that, per this same session's own ADR-0018, was never going to be the credential the
  actual production deployment uses.
- **The counter-argument, stated fairly**: ADR-0014's own comparison work (Vault vs. Infisical vs.
  a custom build) took real effort, and removing the implementation means redoing that evaluation
  from scratch if a genuine future need arises (a second environment, a second operator, a
  compliance requirement `.env` structurally can't satisfy — ADR-0018's own named triggers). This
  is a real cost, not dismissed — but by the time that trigger is real, the secrets-manager
  landscape (pricing, licensing, Vault's own BUSL status, Infisical's own maturity) will likely
  have moved enough that re-evaluating fresh is arguably *more* correct than resurrecting a
  reasoning chain written against today's landscape, not merely "wasted work redone."

## Decision

**Remove Infisical entirely** — not narrow its scope, not leave it dormant. Every piece ADR-0014
added:

- `docker-compose.infisical.yml` deleted.
- `docs/05-engineering/infisical-setup.md` deleted.
- `app/docker-entrypoint.sh` reverts to a single, unconditional `exec java -jar app.jar` — no
  dual-mode branch.
- `app/Dockerfile` drops the Infisical CLI install block entirely.
- `.trivyignore`'s 7 CVE entries removed (their sole cause is gone) — `ci.yml`'s own
  `trivyignores:` input removed alongside it, so Trivy runs with nothing to ignore, not pointed at
  an empty file.
- Every `INFISICAL_*` variable removed from `docker-compose.yml`, `docker-compose.prod.yml`, and
  `.env.example`.
- `risk-register.md`, `vendor-management.md`, `asset-inventory.md`, `access-review.md`,
  `roadmap-and-release-plan.md` updated to describe the real, current state (`.env`-only,
  one fewer named highest-value credential, one fewer optional Compose overlay) — not left
  describing a mechanism that no longer exists.
- `ADR-0014` itself is **not deleted or edited in place** — same "never rewrite a locked decision's
  own history" discipline `technical-debt-register.md` already applies to its own closed rows.
  Marked **Superseded by ADR-0019** at its own Status line; its Context/Decision/Consequences stay
  exactly as originally written, an honest record of what was true and why on 2026-08-24.

Phase 1 of ADR-0014's own two-phase plan (a SOPS+age-encrypted `.env`, doc-only, zero code) was
never built and stays out of scope here too — not resurrected as a consolation option, since the
same "not proportionate to a single-VM, solo-operator deployment yet" reasoning applies to it as
much as it does to Infisical itself. Plain `.env` plus the file-permission hardening
`scripts/host/bootstrap.sh` already applies (`chmod 600`, deploy-user-owned) is where this project
actually stands now.

## Consequences

- **Positive:** 7 fewer accepted CVEs, one fewer open "revisit when the vendor patches" tracking
  item, a smaller/faster/simpler build image, one fewer named highest-value credential to review
  access to, one fewer optional Compose overlay contributing to the operability trend
  `roadmap-and-release-plan.md` already flagged as worth watching.
- **Positive:** `docker-entrypoint.sh` and `app/Dockerfile` both get simpler and more directly
  reviewable — a future external security reviewer sees exactly one secrets-delivery path, not two
  where only one has ever actually run.
- **Negative:** ADR-0014's own comparison work (Vault vs. Infisical vs. custom) is no longer live
  behind a working implementation — a genuine future need for a real secrets manager starts that
  evaluation over, not from a partially-built head start. Accepted, per Context above: the
  landscape will likely have moved enough by then that a fresh evaluation is the more correct move
  anyway, not a wasted one.
- **Negative:** this is now itself a locked decision — resurrecting Infisical (or adopting Vault/
  OpenBao/anything else) later requires its own new ADR, not a quiet re-add.

## Alternatives considered

- **Leave it as-is (dual-mode, dormant).** Rejected — the unconditional CLI install and its 7
  accepted CVEs are a real, ongoing cost paid by every image regardless of whether the dormant path
  is ever used; "already built, zero risk to the working flow" doesn't hold once the build-time and
  governance costs above are counted honestly, not just the runtime ones.
- **Keep the code, drop only the CVE risk-acceptance (e.g. pin a different Infisical CLI version).**
  Rejected — confirmed 2026-08-24 (`.trivyignore`'s own note) that `0.43.125` was already the latest
  available build and no patched-Go-toolchain release existed; there was nothing to re-pin to that
  would have actually closed the CVEs without removing the binary.
- **Resurrect Phase 1 (SOPS+age `.env`) as a middle ground.** Considered and rejected in the
  Decision section above — same proportionality reasoning applies to it as to Infisical itself at
  this project's current single-VM, solo-operator scale.
