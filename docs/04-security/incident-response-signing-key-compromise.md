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

**Read §6 before acting** — the fast, clean, per-tenant containment path ADR-0010 §5.2 describes (`POST /api/v1/organizations/{id}/signing-keys/rotate`) does not exist in the codebase yet. **TD-SEC-002 closed 2026-08-21, which changes what "restart" actually does — read this section in full, not from memory of the pre-fix version.** A plain restart used to force every key pair to regenerate; it no longer does. `SigningKeyStore` now persists real key material to a PKCS12 file, and both `PlatformSigningKeyMaterial`/`OrganizationSigningKeyMaterialFactory` reload it on construction whenever the DB's active row and the keystore both still agree on the same `kid`. A restart with no other action is now a **no-op for containment** — it hands the attacker back the exact same, still-compromised key.

1. Confirm the compromise is real before acting — the procedure below (organization-scoped) is now much cheaper than the old whole-system restart, but is still not something to trigger on a hunch.
2. **Force the reload to fail, then restart.** The reload path only reuses persisted material when *both* the DB's active `SigningKey` row *and* a matching keystore entry are present — breaking either one is sufficient:
   - Retire the compromised Organization's active row: `UPDATE signing_keys SET retired_at = now() WHERE organization_id = '<id>' AND retired_at IS NULL;` (platform tier: same against `platform_signing_keys`, no `organization_id` filter — there is only ever one active row). No HTTP/use-case path does this yet (same class of gap as TD-SEC-018) — direct SQL is the only lever, same honesty this runbook already applies to §3 elsewhere.
   - Restart the application. `ActivateSigningKeyForOrganizationService`/`ActivatePlatformSigningKeyService` see no active row for that scope, generate a brand-new `kid`, and `SigningKeyStore.generate(...)` writes fresh material under it — the compromised `kid` is never looked up again, and (because the retired row's alias is simply left behind, unread) nothing needs the old keystore entry deleted for this to work.
3. **This is now scoped to the compromised Organization only, not system-wide** — the single biggest operational improvement TD-SEC-002 delivered for this runbook. Retiring one Organization's `signing_keys` row and restarting regenerates *that* Organization's key while every other Organization's (and the platform tier's) active row/keystore entry is untouched, so their reload path still finds a match and their tokens are undisturbed. **Exception**: if the compromised material was the *platform* tier's own key, there is still only one platform issuer, so the blast radius of forcing its rotation is inherently system-wide for platform-tier tokens (`client_credentials` against `/oauth2/token`) — but this no longer forces every *Organization's* tokens to rotate too, which the pre-fix version of this runbook could not avoid.
4. The restart itself is still not selective at the process level — every Organization's `PlatformSigningKeyMaterial`/`OrganizationSigningKeyMaterialFactory` beans are reconstructed on any restart. The fix in step 2/3 is what makes that harmless for the *uncompromised* Organizations (their reload path finds their own still-valid row+keystore entry and reuses it) — it is not a claim that the process itself can be restarted for one tenant only.
5. There is still no way to selectively invalidate only the compromised Organization's sessions/refresh tokens (TD-ARCH-002 — session state is in-process, not per-tenant addressable from outside the process) — a restart clears every session process-wide regardless of which Organization's key triggered it.

## 4. Investigation

- Pull every `event=token_issued`/`event=token_revoked` log line for the affected `organizationId` covering the full suspected exposure window (as far back as log retention allows — TD-TEST-002 flags that `event_outbox` has no retention policy yet, but this is application log retention, a separate, deployment-environment concern, not covered by that item).
- The token *value* is never logged (BR-DATA-01) — investigation can establish *volume and pattern* of issuance (grant type, client, principal, timing) but cannot directly confirm which specific historical tokens were forged versus legitimate. Treat any `client_id`/`principal` combination inconsistent with the tenant's own known `OAuthClient`(s) as the primary lead.
- Ask the affected consuming application to correlate their own access logs against the suspected window — they may have observed requests bearing a token this system's own logs show no matching issuance event for, which is itself evidence of forgery (a token verifying successfully but never actually issued by Clavaris).

## 5. Eradication and recovery

1. Complete §3 (retire the row, then restart) if not already done.
2. Confirm eradication: fetch `/o/{organizationId}/oauth2/jwks` (or `/oauth2/jwks` for the platform tier) and confirm the previously-compromised `kid` is no longer present, and that a new `kid` is.
3. Determine and close the actual leak vector (credential scanning gap, log line that shouldn't exist, compromised host) before considering the incident closed — a repeat compromise of the *new* key via the same vector is a process failure, not a technology one.
4. Notify the affected tenant's own operator/engineering contact that a forced re-authentication has occurred and why. Since TD-SEC-002 closed, this is scoped to the compromised Organization's own users, not every tenant's — confirm with the affected consumer specifically rather than broadcasting the old system-wide notice by habit.
5. Every session process-wide is still cleared by the restart itself (§3.5, TD-ARCH-002) — mention this to *every* integrated consumer as a heads-up even though only one Organization's tokens were actually forged/at risk, so their users aren't surprised by an unexplained logout.

## 6. Known limitations of this runbook, given the current implementation

This section exists so the gap between "what ADR-0010 §5 describes" and "what actually runs today" is never rediscovered mid-incident:

| Gap | What it means for this runbook | Tracked as |
|---|---|---|
| No `POST .../signing-keys/rotate` endpoint or use case exists — forcing a new key means retiring the DB row by raw SQL, then restarting the whole process | §3's containment path is real and now Organization-scoped in effect (TD-SEC-002 closed), but still not a safe, self-service, auditable operation — same class of gap as TD-SEC-018 | TD-SEC-010 |
| Even once a rotation endpoint exists, the current key-material factory overwrites the previous key immediately, with no overlap | A *future* targeted rotation, once built, still needs TD-SEC-008 closed first, or it will break every token issued in the seconds before rotation | TD-SEC-008 |
| `OAuth2AuthorizationService` is in-memory only | Authorization-code state for the compromised Organization can't be inspected or selectively invalidated independently of the process-wide restart | TD-SEC-003 |
| Session/request-cache state is in-process | No way to force-expire only the compromised Organization's active browser sessions — §3.5/§5.5 | TD-ARCH-002 |

TD-SEC-002 (signing-key persistence) closed 2026-08-21 and is what turned §3 of this document from "restart invalidates every tenant's tokens" into the Organization-scoped procedure above — this revision *is* that same-day update the previous version of this runbook asked for. TD-SEC-003/008/010/ARCH-002 remain open; TD-SEC-010 (a real, self-service rotation endpoint) is the sharpest of the four left, same reasoning as before: raw SQL against production is still the only lever, just a narrower one now.
