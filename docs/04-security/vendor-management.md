# Vendor / Subprocessor Management — Clavaris

🟡 En revisión

TD-FUT-016 (ISO/IEC 27001 + SOC 2 Type II readiness, ADR-0016): both frameworks require knowing —
and being able to state to an auditor or a consumer's own compliance team — which third parties
touch data this system is responsible for, and why each is trusted. This document is deliberately
short, because the honest answer at this project's current stage is that the list is short — padding
it with hypothetical future vendors would misrepresent readiness, not demonstrate it.

## 1. Distinction: subprocessor vs. tooling

A **subprocessor** processes data this system is responsible for (account data, tokens, PII) on its
own infrastructure. **Tooling** (CI runners, source hosting, static analysis) touches source code and
build artifacts, not runtime user data, and is out of scope for this document — it's a supply-chain
concern (`technical-debt-register.md` §2/§3's own dependency/CI-security rows), not a data-processing
one.

## 2. Real subprocessors today

| Vendor | What it processes | Why trusted | Data it never receives |
|---|---|---|---|
| **Resend** (ADR-0011, TD-SEC-004) | Email address (`MAIL_FROM_ADDRESS` as sender, the account's own address as recipient) and the verification/password-reset link's own token value, for the single HTTP call that sends one email | Purpose-built transactional email API, not a general SMTP relay with broader access; API key scoped to send-only, not account/domain administration | Passwords (plaintext or hashed), refresh tokens, access tokens, signing key material — none of these are ever included in an email body or subject (BR-DATA-01 extends here too, not just to logs) |

That's the complete list as of this writing. No payment processor, no analytics/tracking vendor, no
error-tracking SaaS (Sentry or equivalent) is integrated — genuinely not present, not omitted from
this table by oversight.

## 3. Infrastructure that is not a subprocessor, by design

- **PostgreSQL / Redis** — self-hosted (`docker-compose.yml`) today. The moment either becomes a
  managed cloud service (a real possibility once TD-FUT-013's deployment artifact exists), that
  provider becomes a real subprocessor and belongs in §2 — flagged here so that day isn't missed.
- **The production hosting provider** — not yet chosen or deployed (the runbook names an Oracle
  Cloud Always Free instance as the target). The deployment artifact exists (TD-FUT-013, closed
  2026-08-28) and has run on pre-production since 2026-09-09. The provider necessarily becomes this
  project's highest-access subprocessor the moment it is chosen, since it will have
  infrastructure-level access to the running database: add it to §2 when it is, not afterwards.
- **Pre-production** is a virtual machine the owner operates, reached from the internet through
  Tailscale Funnel, which terminates TLS at its own edge and forwards the traffic to the host, so
  Tailscale sees that environment's traffic in the clear. Acceptable for pre-production, which holds
  no real users' data; it must not be the path for production traffic.

## 4. Tooling (out of scope for §2, listed for completeness)

GitHub (source hosting, Actions CI runners), SonarCloud (static analysis), Docker Hub / base image
registries, `aquasec/trivy` (container scanning) — all touch source code and build artifacts, never
runtime account/credential data. Their own security posture is a supply-chain concern, tracked via
normal dependency/CI hygiene (`technical-debt-register.md`), not this document.

## 5. Review cadence

Reviewed whenever a new vendor is integrated (added to §2 or §4 in the same change, not after the
fact) and at every full `technical-debt-register.md` review cadence otherwise — the list is short
enough that "did anything change" is a fast check, not a burden.
