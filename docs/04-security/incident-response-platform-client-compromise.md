# Incident Response Runbook — `PlatformClient` Bootstrap-Credential Compromise

🟡 En revisión

Closes TD-SEC-006 / `threat-model-stride.md` §6's "no incident-response runbook for `PlatformClient`/bootstrap-credential compromise." This is the single highest-value credential in the system (same framing already used in `Argon2ClientSecretHasher`'s own code comment and `PlatformClientTest`) — it can create, and thereby indirectly reach, every `Organization`. Higher severity than the per-Organization signing-key runbook (`incident-response-signing-key-compromise.md`), since that one is scoped to a single tenant by design and this one structurally isn't.

## 1. Scope and severity

Covers a suspected or confirmed compromise of `PLATFORM_BOOTSTRAP_CLIENT_ID`/`PLATFORM_BOOTSTRAP_CLIENT_SECRET`, or of the resulting `PlatformClient` database row. **P0.** Unlike a tenant signing-key compromise, this credential authenticates against the platform issuer (`{clavarisBaseUrl}/oauth2/...`), which can call `POST /api/v1/admin/organizations` and every other `/api/v1/admin/*` endpoint — an attacker holding it can create rogue Organizations, and every subsequent action taken through one is structurally indistinguishable from a legitimate operator's.

**Trigger conditions** — any of:
- `PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET` found somewhere they shouldn't be (a repo, a log, a compromised deployment-environment secrets store, a compromised host that had them as env vars).
- `event=token_issued` logs (TD-SEC-016) showing `grantType=client_credentials` issuance against the platform issuer at a time or from a context nobody legitimate can account for.
- An `Organization` row exists that no one on the operating team created or requested.

## 2. Detection

- `event=token_issued` logs filtered to the platform issuer's own client_id — any issuance nobody on the operating team can attribute to a known deployment/CI action is a lead.
- Compare the `organizations` table against an independent record of legitimate provisioning requests (support tickets, deployment notes, whatever record-keeping exists outside Clavaris itself) — **this is the primary detection mechanism available today**, because there is no dedicated admin-API audit log yet (TD-SEC-007 is still open; see §6). A rogue `Organization` row is often the first concrete evidence, not a log line.
- Secrets-scanning / credential-leak alerting on the deployment environment holding `PLATFORM_BOOTSTRAP_CLIENT_SECRET` (outside this codebase's own scope, but the fastest possible detection path when it exists).

## 3. Immediate containment

Two independent problems, both real, both need addressing — fixing one does not fix the other:

**3a. Stop the compromised credential from obtaining *new* tokens.**

`BootstrapPlatformClientService.handle()` is deliberately idempotent by `clientId` (BR-PLATFORM-03) — **this is an operational trap during an incident, not just a design detail**: simply rotating `PLATFORM_BOOTSTRAP_CLIENT_SECRET` in the deployment environment and restarting does **nothing** to the existing `PlatformClient` row, because `existsByClientId(...)` short-circuits before the new secret is ever hashed or saved. Confirmed by reading the class directly, not assumed from its Javadoc.

The only way to actually rotate this credential today:
1. Delete the existing `platform_clients` row for the compromised `clientId` — directly via SQL against Postgres. **No HTTP endpoint or use case exists for this** (confirmed: zero revoke/delete/deactivate capability anywhere in `client-registry-module` for `PlatformClient` — see §6, this is itself a new tracked gap, TD-SEC-018).
2. Set new values for `PLATFORM_BOOTSTRAP_CLIENT_ID`/`PLATFORM_BOOTSTRAP_CLIENT_SECRET` in the deployment environment's secrets store — a genuinely new `clientId`, not a same-ID secret swap, is the cleaner choice and avoids any ambiguity about whether step 1 actually completed before restart.
3. Restart the application — `PlatformClientBootstrapRunner` will seed the new `PlatformClient` row on startup (BR-PLATFORM-03's idempotent check now finds no existing row for the new `clientId` and creates it).

**3b. Invalidate tokens *already issued* to the compromised credential.**

Confirmed by reading `AdminApiSecurityConfig`: the management API is a pure JWT resource server (`.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`) — it decodes and verifies the JWT's signature/claims locally on every request and never re-checks the presenting client against `PlatformClientRepository` or any `OAuth2AuthorizationService` state. **Deleting the compromised `PlatformClient` row (3a) does not invalidate a token it already issued** — that token remains valid, against the platform issuer's own JWKS, until it naturally expires.

Two mitigating facts, both worth stating plainly rather than assumed:
- Confirmed via decompiling the resolved SAS 7.1.0 jar: `TokenSettings`'s default `accessTokenTimeToLive` is 5 minutes, and `PlatformRegisteredClientRepository` never overrides it — every platform-tier access token issued by this system expires within 5 minutes of issuance regardless of any containment action taken. Worst case, natural expiry alone closes this exposure window within 5 minutes of the *last* token the attacker obtained.
- If waiting even 5 minutes is unacceptable, restarting the application (already required for 3a's step 3) also regenerates the platform issuer's own signing key (`PlatformSigningKeyMaterial`, in-memory only, TD-SEC-002) — every previously-issued platform-tier token fails signature verification immediately afterward, the same mechanism the signing-key-compromise runbook relies on. **This restart has the same system-wide side effect documented there**: every tenant's own currently-valid tokens are invalidated too, not just the platform tier's — communicate that before restarting, not after.

## 4. Investigation

- Every `event=token_issued` line for the platform issuer's `client_id`, for as far back as retention allows.
- Every row in `organizations` created since the earliest plausible compromise time — for each one not independently confirmed as a legitimate request, treat it as suspect: review any `Account`/`OAuthClient` activity created under it as potentially attacker-controlled infrastructure, not just an unauthorized-but-otherwise-normal tenant.
- **Explicit, honest limitation**: without TD-SEC-007 (management-API audit logging, still open), a full reconstruction of *every* action taken with the compromised credential — not just organization creation — is not achievable from what this system currently records. Any incident write-up produced from this runbook must say so explicitly, not imply a completeness the log data doesn't support.

## 5. Eradication and recovery

1. Complete §3a and §3b if not already done.
2. Confirm eradication: attempt a `client_credentials` request with the old, compromised `clientId`/`secret` pair and confirm it fails (401) — the deleted row means `PlatformRegisteredClientRepository.findByClientId` now resolves to nothing.
3. Confirm the new bootstrap credential works: a fresh `client_credentials` request against the new `clientId`/`secret` succeeds, and `POST /api/v1/admin/organizations` is reachable with the resulting token.
4. Rotate the credential value in whatever secrets manager or CI variable store originally held the compromised one — this is a deployment-environment action outside this codebase's own scope, but is part of actually closing the incident, not optional cleanup.
5. For every suspect `Organization` identified in §4: decide, with the business/operations owner, whether to leave it (if ultimately confirmed legitimate), suspend it, or delete it — this is a judgment call this runbook doesn't make for the operator, since Clavaris has no product opinion on tenant-level business decisions (`vision-document.md` §1).

## 6. Known limitations of this runbook, given the current implementation

| Gap | What it means for this runbook | Tracked as |
|---|---|---|
| No HTTP/use-case path to revoke or rotate a `PlatformClient` — only idempotent create-if-absent | §3a's containment path is raw SQL against production, not a safe, auditable, self-service operation | **TD-SEC-018 (new, opened by writing this runbook)** |
| No management-API audit logging | §4's investigation can establish *that* the credential issued tokens and *which* Organizations exist, but not a full action-by-action timeline | TD-SEC-007 |
| `PlatformSigningKeyMaterial` is in-memory only | §3b's "immediate" invalidation option is a full restart, with the same system-wide side effect as the signing-key-compromise runbook | TD-SEC-002 |
| `PlatformClientRepository`/`OAuth2AuthorizationService` aren't checked per-request | Already-issued tokens survive credential rotation — bounded only by the 5-minute default TTL or a full restart, not by revoking the credential itself | TD-SEC-003 |

Revise this runbook the same day TD-SEC-018 (a real self-service rotation/revocation path for `PlatformClient`) ships — §3a's raw-SQL step is the sharpest gap here and should be the first of these four closed.
