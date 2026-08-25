# Risk Register — Clavaris

🟡 En revisión

## 1. Purpose and scope

TD-FUT-016 (ISO/IEC 27001 + SOC 2 Type II readiness, ADR-0016): a formal risk register — an
explicit list of what could go wrong, how likely/severe each is judged to be, what's actually done
about it today, and who owns it — is a real control both frameworks ask for (ISO Annex A 5.1/6.1,
SOC 2 CC3) that this project has never had in one place before. Every risk below already existed;
this document doesn't invent new ones, it collects what was previously scattered across
`threat-model-stride.md`, `technical-debt-register.md`, and individual ADRs into the single
register an auditor (or the operator, six months from now) can actually read end to end.

**Not duplicated, only referenced**: this register cross-references `threat-model-stride.md` (STRIDE
analysis, the *security*-specific subset of these risks) and `technical-debt-register.md` (the
concrete engineering work items each risk drives) rather than restating either. If a linked
document's own status changes, that document is the source of truth — this register is reviewed
against it, not kept independently in sync by hand.

**Owner**: Engineering (solo project) — every risk below is this one person's to accept, mitigate,
or escalate. That's not a governance gap to paper over; it's stated explicitly because "who owns
this risk" is exactly the question a real risk register has to answer, and for this project the
honest answer is always the same name.

## 2. Scoring

**Likelihood**: Low / Medium / High, over the next 12 months, given today's zero-real-user-traffic
stage — a risk that's High post-launch may score Low today purely because the triggering condition
(real traffic, a real second consumer, real key material handled outside a laptop) doesn't exist
yet. Re-score at the next full review, not on a fixed calendar — `technical-debt-register.md`'s own
cadence ("before every release-boundary decision, and mandatorily before scheduling the external
security review") applies here too.

**Impact**: Low / Medium / High / Critical — Critical reserved for anything that compromises the
`PlatformClient` bootstrap credential or a signing key, consistent with `security-architecture.md`
§3's own framing of those as the system's highest-value targets.

## 3. Register

| Risk | Likelihood | Impact | Mitigation today | Residual risk | Owner |
|---|---|---|---|---|---|
| `PlatformClient` bootstrap credential (`PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET`) compromised | Low | **Critical** — whoever holds it can create/reach every `Organization` (§5, root CLAUDE.md) | Never appears in code, never has an HTTP-reachable creation path, own runbook (`incident-response-platform-client-compromise.md`); Infisical (ADR-0014) removes it from plaintext env vars when configured | No self-service rotation without the runbook's manual steps; TD-SEC-018 closed the "no rotation path at all" gap but rotation is still a manual, audited operation, not automated | Engineering |
| A per-`Organization` signing key compromised | Low today (zero real Organizations with real traffic); rises with each real consumer onboarded | High — single-tenant incident by design (ADR-0010 §5), not Clavaris-wide | Per-tenant issuer/JWKS/key pair (ADR-0010 §5), rotation-with-overlap (TD-SEC-008, closed), own runbook (`incident-response-signing-key-compromise.md`) | v1 rotation is manually-triggered, not unattended (documented, accepted gap, not a silent one) | Engineering |
| Credential-stuffing / brute-force against `/login` or `/oauth2/token` | Medium — public internet-facing the moment any consumer launches | Medium (per-tenant blast radius, not system-wide, by construction) | Two-layer rate limiting (ADR-0010 §6, TD-SEC-001/022/023 all closed), Argon2id (slows offline attacks even if a hash leaks) | Argon2id's own CPU cost is a *capacity* risk under legitimate concurrent load too — see TD-FUT-017 below, a direct consequence of the same design choice | Engineering |
| Argon2id verification cost collapses `/oauth2/token` latency under concurrent load | **Medium, newly measured 2026-08-24** (TD-TEST-004, `load-testing/README.md`) | Medium — availability/NFR risk (p95 < 300ms target), not a confidentiality/integrity risk | Real load-test data now exists (previously: unknown, unmeasured); tracked as TD-FUT-017 | Ceiling not yet raised (more cores, tuned Argon2 params, or an explicit concurrency-vs-security tradeoff decision) — open | Engineering |
| Redis becomes unavailable (single, non-HA container in v1) | Medium — no HA topology exists yet | Medium — rate limiting and session state both depend on it | Fails open by design for rate limiting (TD-SEC-022, closed, live-verified against a dead connection) rather than fails closed and takes login down with it | No HA Redis topology — an accepted v1 scope limit (`security-architecture.md` §4's own "current expected infrastructure" framing), not yet a documented risk acceptance until this row | Engineering |
| Primary Postgres instance lost (hardware failure, operator error, ransomware) | Low — small, largely static estate | **High** — this is the entire credential store; no backup/DR story exists at all | None beyond whatever the eventual hosting provider's own defaults are (TD-FUT-013, deployment artifact, still open) | **Real, unmitigated gap** — TD-FUT-006 (backup/DR design) is still open and correctly the register's own most consistently flagged availability risk | Engineering |
| Resend (email subprocessor) outage or account compromise | Low | Medium — blocks verification/password-reset email delivery; does not expose credentials (Resend never receives a password or token, only an address and a link) | `vendor-management.md` §2 documents the exposure precisely | No fallback mail provider — accepted, single-vendor dependency for v1 | Engineering |
| Solo-developer bus factor — every finding in this project's own register has been caught by the same person who introduced it (technical-debt-register.md's own 2026-08-24 finding) | High (structural, not a point-in-time event) | Medium — no single incident, but a standing quality/security gap | CI (SonarCloud, OWASP dependency-check, Trivy, ArchUnit, PMD) acts as a partial automated second reviewer | Real, structural, explicitly accepted as this project's own operating model for now (`project-charter.md` §5's own "solo developer" framing) — not solvable without a second person | Engineering |
| A new consumer's traffic pattern differs materially from JobSeeker's (the only one validated against) | Medium once a second real consumer exists | Low–Medium — most-plausible failure mode is a capacity surprise, not a security one | Two-layer rate limiting's own per-Organization capacity ceiling (ADR-0010 §6.2) exists precisely for this | Self-service tuning is v1.1 (TD-FUT-002); today an operator has to intervene manually for an outlier tenant | Engineering |

## 4. Review cadence

Same cadence as `technical-debt-register.md` §0's own lifecycle rules — re-scored at every full
register review, and mandatorily before the external security review is scheduled
(`security-architecture.md` §9). A risk resolved by a specific engineering change should reference
that change's own `technical-debt-register.md` row rather than being described twice.
