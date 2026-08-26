# Incident Response Runbook — `PlatformClient` Bootstrap-Credential Compromise

🟡 En revisión

Closes TD-SEC-006 / `threat-model-stride.md` §6's "no incident-response runbook for `PlatformClient`/bootstrap-credential compromise." This is the single highest-value credential in the system (same framing already used in `Argon2ClientSecretHasher`'s own code comment and `PlatformClientTest`) — it can create, and thereby indirectly reach, every `Organization`. Higher severity than the per-Organization signing-key runbook (`incident-response-signing-key-compromise.md`), since that one is scoped to a single tenant by design and this one structurally isn't. **Corrected 2026-08-26**: §3a used to direct raw SQL against production for containment and cite that as an open gap (TD-SEC-018) — TD-SEC-018 closed 2026-08-23, and this **P0** runbook sat un-updated for three days despite its own closing paragraph explicitly promising a same-day revision when that happened. Found and fixed the same day as the equivalent, lower-severity staleness in the signing-key runbook (`technical-debt-register.md` TD-PROC-008).

## 1. Scope and severity

Covers a suspected or confirmed compromise of `PLATFORM_BOOTSTRAP_CLIENT_ID`/`PLATFORM_BOOTSTRAP_CLIENT_SECRET`, or of the resulting `PlatformClient` database row. **P0.** Unlike a tenant signing-key compromise, this credential authenticates against the platform issuer (`{clavarisBaseUrl}/oauth2/...`), which can call `POST /api/v1/admin/organizations` and every other `/api/v1/admin/*` endpoint — an attacker holding it can create rogue Organizations, and every subsequent action taken through one is structurally indistinguishable from a legitimate operator's.

**Trigger conditions** — any of:
- `PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET` found somewhere they shouldn't be (a repo, a log, a compromised deployment-environment secrets store, a compromised host that had them as env vars).
- `event=token_issued` logs (TD-SEC-016) showing `grantType=client_credentials` issuance against the platform issuer at a time or from a context nobody legitimate can account for.
- An `Organization` row exists that no one on the operating team created or requested.

## 2. Detection

- `event=token_issued` logs filtered to the platform issuer's own client_id — any issuance nobody on the operating team can attribute to a known deployment/CI action is a lead.
- `event=audit_recorded action=organization.created`/`action=platform_client.deactivated`/`action=platform_client.secret_rotated` log lines (TD-SEC-007, closed 2026-08-23) — a real, queryable, per-actor and per-target audit trail exists today, not just the `organizations` table itself. Cross-check against an independent record of legitimate provisioning requests (support tickets, deployment notes) for anything the audit trail can't itself judge as authorized. A rogue `Organization` row remains valuable corroborating evidence, just no longer the *only* detection mechanism available.
- Secrets-scanning / credential-leak alerting on the deployment environment holding `PLATFORM_BOOTSTRAP_CLIENT_SECRET` (outside this codebase's own scope, but the fastest possible detection path when it exists).

## 3. Immediate containment

**Corrected 2026-08-26** — §3a below used to direct raw SQL against production, describing itself as the only option and citing that as a new gap, TD-SEC-018. TD-SEC-018 closed 2026-08-23 and this section was never revised, despite this runbook's own closing paragraph explicitly promising it would be the day that happened. Same class of staleness this project's own `incident-response-signing-key-compromise.md` was independently found and fixed for one day earlier (`technical-debt-register.md` TD-PROC-008) — worth taking especially seriously here, since this runbook is **P0**, the highest severity in the system.

Two independent problems, both real, both need addressing — fixing one does not fix the other:

**3a. Stop the compromised credential from obtaining *new* tokens.**

A real, self-service, audited revocation endpoint exists — no SQL, no restart:

1. `POST /api/v1/admin/platform-clients/{clientId}/revoke`, authenticated with a *different*, still-trusted platform-tier token carrying the `platform:platform-clients:revoke` scope. Response: `204` on success, `404` if the given `clientId` doesn't exist. Records a real, audited `platform_client.deactivated` event (TD-SEC-007).
2. `DeactivatePlatformClientService` sets `active=false` on the row (not a delete) — `PlatformRegisteredClientRepository` (app module) treats an inactive client as not-found the moment the *next* `client_credentials` exchange is attempted against it, closing off new tokens immediately. Confirmed live-equivalent, not assumed: `findById` is deliberately **not** filtered by `active` (unlike `findByClientId`), so revoking a client doesn't also block *this same revoke call's own* ability to look the client back up if needed — a real correctness bug caught before TD-SEC-018 shipped, not a coincidence.
3. Provision a genuinely new bootstrap credential rather than reusing the compromised `clientId`: set new values for `PLATFORM_BOOTSTRAP_CLIENT_ID`/`PLATFORM_BOOTSTRAP_CLIENT_SECRET` in the deployment environment's secrets store and restart — `PlatformClientBootstrapRunner`'s own idempotent-by-`clientId` check (BR-PLATFORM-03) finds no existing row for the new id and creates it. **Do not attempt to "rotate in place"** via `POST /api/v1/admin/platform-clients/{clientId}/rotate-secret` for a *compromise* specifically — that endpoint is for routine credential hygiene on a client that's still trusted; for an actual compromise, revoke (step 1) and provision fresh (this step) is the correct pair, so the compromised `clientId` itself stops being usable rather than merely getting a new secret an attacker who already has broader access could plausibly intercept again.

**3b. Invalidate tokens *already issued* to the compromised credential.**

Confirmed by reading `AdminApiSecurityConfig`: the management API is a pure JWT resource server (`.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`) — it decodes and verifies the JWT's signature/claims locally on every request and never re-checks the presenting client against `PlatformClientRepository` or any `OAuth2AuthorizationService` state. **Deleting the compromised `PlatformClient` row (3a) does not invalidate a token it already issued** — that token remains valid, against the platform issuer's own JWKS, until it naturally expires.

One mitigating fact, worth stating plainly rather than assumed:
- Confirmed via decompiling the resolved SAS 7.1.0 jar: `TokenSettings`'s default `accessTokenTimeToLive` is 5 minutes, and `PlatformRegisteredClientRepository` never overrides it — every platform-tier access token issued by this system expires within 5 minutes of issuance regardless of any containment action taken. Worst case, natural expiry alone closes this exposure window within 5 minutes of the *last* token the attacker obtained.

**TD-SEC-002 closed 2026-08-21 — the restart-based fast path this runbook used to describe here no longer works, and this section must not be read from memory of the pre-fix version.** `PlatformSigningKeyMaterial` now reloads its key material from a persisted PKCS12 file (`SigningKeyStore`) whenever the active `platform_signing_keys` row and the keystore still agree — a plain restart alone **no longer regenerates the platform signing key**; it hands back the exact same key. If waiting the 5 minutes above is unacceptable, forcing a genuinely new platform key requires retiring the active row first: `UPDATE platform_signing_keys SET retired_at = now() WHERE retired_at IS NULL;`, *then* restarting — `ActivatePlatformSigningKeyService` finds no active row, generates a fresh `kid`, and every previously-issued platform-tier token fails signature verification from that point on. This is the same procedure `incident-response-signing-key-compromise.md` §3 now documents for a per-Organization key, applied to the platform tier's own single row. Because TD-SEC-002 also fixed the Organization side of this (each Organization's own row+keystore entry survives a restart untouched), forcing the platform key alone **no longer forces every tenant's tokens to rotate too** — a real improvement over what this runbook used to have to warn about, though every session process-wide (TD-ARCH-002) is still cleared by any restart regardless.

## 4. Investigation

- Every `event=token_issued` line for the platform issuer's `client_id`, for as far back as retention allows.
- Every row in `organizations` created since the earliest plausible compromise time — for each one not independently confirmed as a legitimate request, treat it as suspect: review any `Account`/`OAuthClient` activity created under it as potentially attacker-controlled infrastructure, not just an unauthorized-but-otherwise-normal tenant.
- `event=audit_recorded` (TD-SEC-007, closed) now covers every management-API action this runbook's own §6 originally named as unrecorded — `organization.created`, `rate_limit_policy.set`, `signing_key.rotated`, `platform_client.secret_rotated`, `platform_client.deactivated` — giving a real, actor-and-target-indexed timeline, not just organization-creation evidence. Still an honest limitation worth stating: `detail` is deliberately never the raw secret/token value (BR-DATA-01), so investigation establishes *what actions were taken and by which credential*, not the literal request/response payloads.

## 5. Eradication and recovery

1. Complete §3a and §3b if not already done.
2. Confirm eradication: attempt a `client_credentials` request with the old, compromised `clientId`/`secret` pair and confirm it fails (401) — the revoke in §3a means `PlatformRegisteredClientRepository.findByClientId` now filters the row out as inactive and resolves to nothing, the SPI's own "not found" convention.
3. Confirm the new bootstrap credential works: a fresh `client_credentials` request against the new `clientId`/`secret` succeeds, and `POST /api/v1/admin/organizations` is reachable with the resulting token.
4. Rotate the credential value in whatever secrets manager or CI variable store originally held the compromised one — this is a deployment-environment action outside this codebase's own scope, but is part of actually closing the incident, not optional cleanup.
5. For every suspect `Organization` identified in §4: decide, with the business/operations owner, whether to leave it (if ultimately confirmed legitimate), suspend it, or delete it — this is a judgment call this runbook doesn't make for the operator, since Clavaris has no product opinion on tenant-level business decisions (`vision-document.md` §1).

## 6. Known limitations of this runbook, given the current implementation

This section exists so the gap between "what this runbook describes" and "what actually runs today" is never rediscovered mid-incident — that exact failure happened to this section itself (2026-08-21 through 2026-08-25): it kept citing TD-SEC-007/018 as open, and TD-SEC-010 as the tracking id for a gap TD-SEC-010 was never actually about (`RegisteredClientRepository.findById`, a different, already-closed finding — the real rotation/revocation gap was always TD-SEC-018's own scope). Re-verify every row here against `technical-debt-register.md` §2/§6 directly before trusting it mid-incident.

| Gap | What it means for this runbook | Tracked as |
|---|---|---|
| No self-service way to force-rotate the *platform* tier's own signing key (distinct from revoking the `PlatformClient` credential itself) | §3b's forced-invalidation option is still a raw-SQL `UPDATE platform_signing_keys` plus a restart, not a safe, auditable, one-click action — the per-Organization equivalent (`incident-response-signing-key-compromise.md`) has had a real endpoint since TD-SEC-008 closed; the platform tier's own key never got the same treatment | Not yet tracked as its own row — see `incident-response-signing-key-compromise.md` §6 for the equivalent, already-tracked per-Organization case |
| `PlatformClientRepository`/`OAuth2AuthorizationService` aren't checked per-request against active-token state | Already-issued tokens survive §3a's revoke — bounded only by the 5-minute default TTL or the same forced key-retirement-plus-restart §3b describes, not by revoking the credential itself | TD-SEC-003's own persistence closed the storage half of this; the per-request re-check this row describes was never separately promised and isn't expected to exist — SAS's own resource-server model is stateless-by-design (decode-and-verify the JWT locally, never a live lookup per request), the same tradeoff every JWT-based system makes for performance, not an oversight specific to this codebase |

Real, closed, and safe to rely on today, not listed as limitations: self-service `PlatformClient` revoke/rotate with real audit logging (TD-SEC-018, TD-SEC-007), persistent signing-key material surviving a restart (TD-SEC-002), and persistent `OAuth2Authorization` state surviving a restart (TD-SEC-003) — all closed 2026-08-21–23. §3/§4 above now reflect that reality directly.
