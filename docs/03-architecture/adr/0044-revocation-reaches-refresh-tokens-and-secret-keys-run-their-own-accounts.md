# ADR-0044: Revocation reaches refresh tokens, and an Organization's Secret Key runs its own accounts and webhooks

**Status:** 🟡 Pending review (2026-11-16)

Two findings of the JobSeeker integration spike, one decision each. Both were found against an isolated instance by the first consumer, not by Clavaris's own tests.

## 1. `/oauth2/revoke` and RP-Initiated Logout end the Session's refresh tokens (TD-SEC-100)

### Context

A refresh token's source of truth is the `refresh_tokens` table (BR-ID-03), single-use and rotated on every exchange. Spring Authorization Server's revocation only searches `oauth2_authorization`, and rotation never writes the refresh token there (only the access and ID token it minted). The stock provider therefore found nothing for a refresh token, answered 200 as RFC 7009 §2.2 requires for an unknown token, and the token went on minting new ones: 4 of 4 variants of the request (with and without `token_type_hint`), and after RP-Initiated Logout. What did work, which is what hid it: the reuse of an already-rotated token (the family is revoked) and the suspension or deletion of the account.

### Decision

1. **Revocation of a refresh token ends its Session.** A new use case, `RevokeRefreshToken`, finds the presented value in `refresh_tokens` and revokes every live token of that Session and the Session itself, not only the presented row: a client may hold a rotated-away predecessor of the live token, and the live one is what has to die. A presented token that is already revoked still ends the chain.
2. **It runs first, in front of the stock provider.** `RefreshTokenRevocationAuthenticationProvider` is tried first on the revocation endpoint. It acts only for an authenticated client on an Organization's endpoint and only when the value is a refresh token of **that** Organization; in every other case it answers `null`, which hands the request to the stock provider unchanged. Access and ID tokens revoke as before, client-authentication failures keep their answers, and an unknown or foreign value is a 200 that is indistinguishable from a real revocation, so the endpoint is no oracle. `token_type_hint` stays a hint (RFC 7009 §2.1).
3. **Logout ends the Session as well.** `SessionRevokingLogoutAuthenticationProvider` wraps the stock logout provider. Only a request that provider accepted (it validates the `id_token_hint` and the client) reaches the new `RevokeSessionBySid` use case, which ends the Session whose ID tokens carry that `sid`, or whose own id is the `sid` of a Session that never recorded one. A refused or forged hint revokes nothing, and the provider's own answer (the redirect, the browser-session handling) is returned untouched.
4. **The tenant boundary holds.** Both use cases compare the Organization of the token's or Session's Account with the Organization whose endpoint received the call (ADR-0010).

### Consequences

- A stolen refresh token can be neutralised by the user's logout, by the client, or by an operator, without suspending the account. Consumers no longer need the workaround of keeping the token server-side with a short session.
- **Access tokens already issued stay valid until they expire** (RFC 7009 says a server SHOULD revoke them too). There is no link from a Session to the `oauth2_authorization` rows it produced, so it is not done here; the access-token lifetime is the bound.
- The tenant refresh grant itself does not compare the client's Organization with the token's Account (the client is resolved inside the request's Organization, and the raw token is a 256-bit secret), so it is not a way in; this ADR leaves it as it was.

## 2. An `OrganizationClient` may suspend, reactivate and delete its own accounts, and run its own webhook endpoints (TD-SEC-101)

### Context

`OrganizationClientOwnershipFilter` is fail-closed: an endpoint it was not told about is unreachable by a token that carries an `organization_id`. Its allowlist let a Secret Key register and list webhook endpoints, read an account's profile and a few more, and answered 403 to deleting or suspending an account, to reading a webhook delivery, to replaying one, to rotating a secret and to switching an endpoint on or off. The only credential able to delete a user was therefore a `PlatformClient`, which is platform-wide: a consumer that must honour an erasure request (JobSeeker: a grace period, then anonymise and delete the real identity) would have had to hold a credential that acts on every Organization, and a leak of that consumer's secret would have been a platform incident.

### Decision

Extend the allowlist, nothing else, with the routes of the consumer's own lifecycle:

| Route | Scope (already enforced by the controller's rule) |
|---|---|
| `POST /api/v1/admin/accounts/{id}:delete` | `platform:accounts:delete` |
| `POST /api/v1/admin/accounts/{id}:suspend`, `:reactivate` | `platform:accounts:suspend` (one scope for both directions) |
| `POST /api/v1/admin/webhook-endpoints/{id}:activate`, `:deactivate`, `:rotate-secret` | `platform:webhook-endpoints:write` |
| `GET /api/v1/admin/webhook-endpoints/{id}/deliveries` | none (reads are unscoped on this surface) |
| `POST /api/v1/admin/webhook-endpoints/{id}/deliveries/{deliveryId}:replay` | `platform:webhook-deliveries:replay` |

1. **Each route resolves its target to an Organization** (an account's, or a webhook endpoint's) and the filter compares it with the token's `organization_id`: another Organization's target is 403, and so is one that does not exist or whose id is not a UUID (fail closed, no oracle between "yours" and "nobody's"). The replay's use case additionally checks that the delivery belongs to the endpoint in the path.
2. **The scopes did not change.** All four were already in `ORGANIZATION_CLIENT_ALLOWED` (none is operator-only), and each controller already required its scope; the ownership filter was the only thing in the way. A Secret Key minted without `accounts:delete` still cannot delete its own account.
3. **Stays forbidden:** deleting an Organization, creating or rotating signing keys, the operator-only policies, and every route that is not listed.
4. **`:reactivate` and `:deactivate` come with their pairs.** The row named delete, suspend, activate; a suspension nobody on the consumer's side can lift, or an activation without its counterpart, is a trap.

### Alternatives considered

- A per-consumer `PlatformClient` with narrowed scopes: still platform-wide reach, and one more thing to rotate and revoke.
- The consumer deleting through a human operator: does not scale and breaks a 30-day grace-period batch.

## Tests

`RefreshTokenRotationIntegrationTest` (revoke, then refresh, for each hint and for a rotated chain; another Organization's client cannot revoke; an unknown token is 200), `UserInfoAndEndSessionIntegrationTest` (logout ends the live token; a refused hint revokes nothing), `OrganizationClientErasureIntegrationTest` (own accounts and endpoints are reachable, another Organization's are 403 and untouched, the Organization itself stays out of reach, the scope still gates), and unit tests for each provider, use case and route.
