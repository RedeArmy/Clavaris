# Incident Response Runbook — Per-Organization Signing Key Compromise

🟡 En revisión

Closes TD-SEC-005 / `threat-model-stride.md` §6's "no incident-response runbook for signing-key compromise." Written against the system as it actually exists today — every step below was checked against real code, not the eventual ADR-0010 §5.2 design, and the two diverge in ways that matter operationally (see §6).

## 1. Scope and severity

Covers a suspected or confirmed compromise of one `Organization`'s RS256 private key material (`SigningKey`, ADR-0010 §5). **P1** (`threat-model-stride.md` §2's own severity for this row) — serious, but by design (ADR-0010 §5) scoped to one tenant, not the whole system. Contrast with the `PlatformClient` runbook (`incident-response-platform-client-compromise.md`), which is higher severity precisely because it isn't scoped to one tenant.

**Trigger conditions** — any of:
- Key material found somewhere it shouldn't be (committed to a repo, exposed in a log despite BR-DATA-01, found on a paste site, present on a compromised host).
- `/o/{organizationId}/oauth2/jwks` serving a key (`kid`) that doesn't correspond to any row this Organization's own `SigningKey` metadata tracks.
- A token verifies successfully against a tenant's JWKS that this Organization's own `event=token_issued` logs (TD-SEC-016) have no matching record of ever issuing.
- The affected consuming application reports unexplained account activity consistent with forged tokens.

## 2. Detection

- `event=token_issued` / `event=token_revoked` logs (TD-SEC-014/016/017), filtered by `organizationId`, for anything that doesn't match the pattern of that tenant's own known `OAuthClient`(s) and expected traffic volume.
- `event=login_success` / `event=login_failure` logs for the same `organizationId` around the suspected exposure window — a forged token doesn't require a real login, so a gap between login volume and token-issuance volume is itself a signal.
- Manual comparison of `/o/{organizationId}/oauth2/jwks`'s published `kid` against the `SigningKey` table row(s) for that Organization.

## 3. Immediate containment

**Read §6 before acting** — the fast, clean, per-tenant containment path ADR-0010 §5.2 describes (`POST /api/v1/organizations/{id}/signing-keys/rotate`) does not exist in the codebase yet. The only containment lever available today is a full application restart, and it is not scoped to the affected Organization.

1. Confirm the compromise is real before acting — a full restart has a real, system-wide cost (below), not something to trigger on a hunch.
2. **If containment is confirmed necessary: restart the application process.** `PlatformSigningKeyMaterial` and `OrganizationSigningKeyMaterialFactory` both generate fresh RSA key pairs in memory at construction time (TD-SEC-002) — a restart is, today, the only way to force a new key pair for the compromised Organization, and it is the fastest fully-effective action available: the old `kid` stops appearing in that Organization's JWKS immediately, so any token signed under the compromised key fails signature verification from that moment on, regardless of the token's own stated expiry.
3. **Before restarting, communicate the actual blast radius**: this action invalidates every currently-valid access/ID token for **every** Organization simultaneously, not just the compromised one — a direct, known consequence of TD-SEC-002 (documented there as "a routine deploy is currently indistinguishable from a mass logout"). Every consuming application integrated with Clavaris should be told to expect a forced re-authentication of all their active users, not just the one tenant whose key was actually compromised. This is the sharpest, most concrete cost of TD-SEC-002/003/008 still being open — see §6.
4. There is no way today to selectively invalidate only the compromised Organization's sessions/refresh tokens either (TD-ARCH-002 — session state is in-process, not per-tenant addressable from outside the process).

## 4. Investigation

- Pull every `event=token_issued`/`event=token_revoked` log line for the affected `organizationId` covering the full suspected exposure window (as far back as log retention allows — TD-TEST-002 flags that `event_outbox` has no retention policy yet, but this is application log retention, a separate, deployment-environment concern, not covered by that item).
- The token *value* is never logged (BR-DATA-01) — investigation can establish *volume and pattern* of issuance (grant type, client, principal, timing) but cannot directly confirm which specific historical tokens were forged versus legitimate. Treat any `client_id`/`principal` combination inconsistent with the tenant's own known `OAuthClient`(s) as the primary lead.
- Ask the affected consuming application to correlate their own access logs against the suspected window — they may have observed requests bearing a token this system's own logs show no matching issuance event for, which is itself evidence of forgery (a token verifying successfully but never actually issued by Clavaris).

## 5. Eradication and recovery

1. Complete the restart from §3 if not already done — this is both the containment and the eradication step today, since there is no separate "targeted key destruction" action available.
2. Confirm eradication: fetch `/o/{organizationId}/oauth2/jwks` and confirm the previously-compromised `kid` is no longer present.
3. Determine and close the actual leak vector (credential scanning gap, log line that shouldn't exist, compromised host) before considering the incident closed — a repeat compromise of the *new* key via the same vector is a process failure, not a technology one.
4. Notify the affected tenant's own operator/engineering contact that a forced re-authentication has occurred and why, plus the fact that (per §3.3) every other tenant experienced the same forced re-authentication as an unavoidable side effect.

## 6. Known limitations of this runbook, given the current implementation

This section exists so the gap between "what ADR-0010 §5 describes" and "what actually runs today" is never rediscovered mid-incident:

| Gap | What it means for this runbook | Tracked as |
|---|---|---|
| No `POST .../signing-keys/rotate` endpoint exists | §3's only containment lever is a full restart, not a targeted per-Organization action | TD-SEC-010 (adjacent — `RegisteredClientRepository.findById` throwing is the same "not built yet" class of gap on the client side) |
| Signing keys are in-memory only, regenerated fresh on every restart, for **every** Organization at once | The one containment action available is not scoped to the compromised tenant — see §3.3 | TD-SEC-002 |
| Even once a rotation endpoint exists, the current key-material factory overwrites the previous key immediately, with no overlap | A *future* targeted rotation, once built, still needs TD-SEC-008 closed first, or it will break every token issued in the seconds before rotation | TD-SEC-008 |
| `OAuth2AuthorizationService` is in-memory only | Authorization-code state for the compromised Organization can't be inspected or selectively invalidated independently of the process-wide restart | TD-SEC-003 |
| Session/request-cache state is in-process | No way to force-expire only the compromised Organization's active browser sessions | TD-ARCH-002 |

Closing TD-SEC-002/003/008 (already `technical-debt-register.md` §5's recommended next priority, independent of this runbook) is what turns §3 of this document from "restart the whole system" into the actual single-tenant-scoped procedure ADR-0010 §5 was designed to allow. Revise this runbook the same day that work ships — an outdated runbook that still tells the next on-call engineer to restart the whole system when a real fix exists is worse than no runbook.
