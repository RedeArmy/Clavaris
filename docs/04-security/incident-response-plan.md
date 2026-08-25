# Incident Response Plan (General) — Clavaris

🟡 En revisión

TD-FUT-016 (ISO/IEC 27001 + SOC 2 Type II readiness, ADR-0016): the two existing runbooks —
`incident-response-signing-key-compromise.md` and `incident-response-platform-client-compromise.md`
— are deep, correct, and specific to exactly one scenario each. Neither answers a more basic
question an auditor (or the operator, at 2am) actually needs answered first: **when something
*unspecified* goes wrong, what's the process, in what order, who does it, and what happens after?**
This document is that process. It doesn't replace either runbook — a signing-key or `PlatformClient`
compromise still follows its own runbook's specific steps once triage below identifies it as one of
those two scenarios; this document is the layer above them.

## 1. Roles

Honest, not aspirational: this is a solo-developer project (`project-charter.md` §5). Every role
below is the same one person. Listed anyway, not as filler, but because a real incident-response
process needs to name the roles even when they collapse onto one name — an auditor checking for
this control is checking that the *function* is defined, not that a team of five exists.

| Role | Responsibility | Who (today) |
|---|---|---|
| Incident commander | Owns the response end to end, makes containment/escalation calls | Engineering (sole developer) |
| Technical responder | Executes containment/eradication steps | Same person |
| Communicator | Notifies affected consumer(s) (JobSeeker today; whichever consuming applications exist at the time), Resend if the incident implicates it | Same person |

**The real, tracked gap this creates**: no second person exists to catch a mistake made under
incident pressure, or to act if the one person is unavailable. Named explicitly in
`risk-register.md` §3 ("solo-developer bus factor") rather than hidden by writing a role table that
implies more capacity than exists.

## 2. Severity classification

Reuses `threat-model-stride.md` §2's own severity scale — not a second, competing one:

| Severity | Meaning | Example |
|---|---|---|
| **P0** | Active exploitation, credential/token material confirmed exposed, or a live correctness bug affecting every tenant | `PlatformClient` bootstrap credential found outside its expected location |
| **P1** | Serious, but scoped (one tenant, one non-critical path) or not yet confirmed exploited | One `Organization`'s signing key suspected (not confirmed) compromised |
| **P2** | Real but bounded — a single account, a single failed control that didn't lead anywhere | One refresh-token-reuse-detection event for one account |

## 3. Detection sources

**Updated 2026-08-24 — TD-FUT-011/ADR-0015 closed, no longer "manually reviewed."** Every
authentication event this project logs structurally (`event=login_success`/`login_failure`/
`token_issued`/`token_revoked`/`refresh_token_reuse_detected`/`rate_limit_fail_open`) is now also a
real Prometheus counter with a real Alertmanager rule behind the highest-severity ones
(`RefreshTokenReuseDetected`, `RateLimitFailOpen` — both fire on any occurrence, real-email-verified
end to end, `infra/observability/alert-rules.yml`) — a P0/P1 incident from this list should now
usually announce itself via a real alert email, not require someone to be reading logs in real time.
The structured logs themselves remain the detailed forensic record once an alert fires — this
doesn't replace log review, it removes the requirement that a human be watching logs continuously
for the incident to be *noticed* in the first place. Full per-request tracing (Zipkin, same
ADR-0015) is a secondary detection/investigation aid — correlating a suspicious log line's own
`traceId` to its full request timeline is now possible, not just the single event line. A report
from a consuming application (JobSeeker or any future consumer) reporting unexplained account
activity is an equally valid, equally weighted detection source — don't assume automated alerting
is the only way an incident surfaces.

## 4. Process

1. **Triage** — is this a known scenario with its own runbook? If the suspected exposure is signing-
   key material, go to `incident-response-signing-key-compromise.md` now; if it's the `PlatformClient`
   bootstrap credential, go to `incident-response-platform-client-compromise.md` now. Both are more
   specific and more correct than anything general written here. Otherwise, continue below.
2. **Classify** — assign a severity (§2) based on what's confirmed, not what's feared; re-classify as
   more information arrives rather than anchoring on the first guess.
3. **Contain** — stop the bleeding first, understand root cause second. For anything touching a
   credential, token, or key, "can this specific value be revoked/rotated without a wider blast
   radius" is the first question — `security-architecture.md` §3's per-tenant isolation (ADR-0010 §5)
   exists specifically so this question usually has a narrow answer.
4. **Eradicate** — fix the actual root cause, not just the symptom that was contained. If the root
   cause is a code defect, it gets a regression test proving it, same standard as every other bug
   fix in this codebase (`test-strategy.md`).
5. **Recover** — confirm the affected system is back to a known-good state; confirm with the
   affected consumer(s) if they were notified.
6. **Communicate** — any consuming application whose accounts, tokens, or data may have been
   affected gets told what happened, when, and what they need to do — before the retrospective
   below, not after. `Organization`-scoped isolation (ADR-0010) means this is almost always "notify
   the one affected tenant," not every consumer.
7. **Post-incident retrospective** — every P0/P1 incident gets a short written record: what
   happened, when detected, when contained, root cause, what changes as a result. Add a
   `technical-debt-register.md` row for any follow-up work the incident surfaces, the same way
   writing the two specific runbooks themselves surfaced TD-SEC-018 — an incident-response exercise
   that doesn't produce at least one such finding almost certainly wasn't looked at hard enough.

## 5. What's still a real gap

Named here rather than implied away: no on-call rotation or paging exists (there is one person),
no formal SLA for response time exists, and no incident has actually happened yet to prove this
process works under real pressure rather than on paper — the two specific runbooks were each
validated by "would this actually work," not by a real incident. Consistent with
`nfr-quality-attributes.md` §7's own "on-call/error-budget process... still unresolved" framing —
this document narrows that gap without fully closing it.
