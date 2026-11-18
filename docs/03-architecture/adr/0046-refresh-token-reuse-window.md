# ADR-0046: A per-Organization reuse window for a refresh token whose rotation response was lost

**Status:** 🟡 Pending review (2026-11-20)

Amends BR-ID-03 (single-use refresh tokens, reuse is theft). Found by the JobSeeker margin experiment (TD-SEC-103).

## Context

The refresh grant rotates a token unconditionally and treats a second presentation of the consumed token as theft: it revokes the whole Session and emits `refresh_token.reuse_detected`. That is right against a thief and wrong against an honest client whose rotation response never arrived. The server completes the rotation, the client times out, and the token it still holds is now "reuse". Reproduced with the real Spring Security client: a proxy delaying the answer past the client's read timeout, a stopped process, a stopped database. Every slow incident ended with every active user signing in again, and a client cannot tell that `invalid_grant` from a suspension, so it has to close the session in both cases.

## Decision

1. **A window, per Organization, off by default.** `session_policies.refresh_token_reuse_grace_seconds`, 0 to 60, default 0, alongside the other session settings (`SessionPolicy`, `SessionPolicySnapshot`). 0 is exactly BR-ID-03 as it was, so an Organization that does nothing changes nothing. The setting lives with the policy the rotation already reads, and it is written through the operator-only `platform:session-policy:write` scope (`PlatformScopes.OPERATOR_ONLY`), so **no tenant can loosen it itself**, the same posture CLAUDE.md §6 takes for rate limits. The 60 s ceiling is enforced in the domain, the API, the dashboard form and a `CHECK` constraint.
2. **What happens inside the window.** A presented token that was rotated away at most `N` seconds ago, **whose successor is still unused**, is answered with a **new** token instead of ending the Session. It cannot be "the same successor": only the hash of a token is stored, the raw value exists only in the response that was lost. So the successor nobody received is retired (`revokeIfActive`, the same atomic guard the rotation race already uses), and a new one is issued from the presented token. The Session is touched, nothing else is revoked, and the usual checks run first and in the same order: the Account is active, the Session is within its Organization's lifetime and inactivity policy, the requested scopes are within the authorized ones.
3. **Everything else is unchanged.** Outside the window, with the window off, when the successor was already used (the real client moved on, so this is someone else), when it has expired, or when a concurrent call consumes the successor between the look and the revoke: the reuse outcome as before, the whole Session revoked, `refresh_token.reuse_detected`.
4. **Repeated retries are allowed inside the window.** A client that also loses the second response retries again and is answered again, until the window closes (counted from the moment the presented token was rotated away). Allowing exactly one would only move the failure to the second lost response.
5. **A distinct, observable signal.** `refresh_token.reuse_graced` (outbox event, subscribable webhook type), `clavaris.auth.refresh_token.reuse_graced` (counter) and the log event `refresh_token_reuse_graced`, so a theft is not hidden among retries and an operator can watch the rate.

## What this trades, stated plainly

During the window, **whoever holds the old token gets a valid session**. A thief who presents it before the real client does wins that race; the real client's next use of the token it did receive then finds its own successor retired and fails, and the Session is not revoked by it. The protection that remains is the window's width and the unused-successor rule: once the real client has used what it holds, the old token is reuse and ends everything. That is why the window is off by default, capped at 60 s (the top of what Auth0 and Okta document), and operator-only. An Organization turns it on knowingly, for the clients that need it (JobSeeker, a mobile app on a poor network), not for everyone.

## Alternatives considered

- **Per OAuth client** (what the register row first proposed): more precise, but a new column, API and dashboard section on the client, and it would let whoever registers a client loosen the rule. The Organization's session policy already carries the rotation's other settings and already has an operator-only write path.
- **A global property:** simplest, and wrong for a platform whose tenants have different clients and different risk.
- **Idempotency by request id:** needs the client to send one, which a standard OIDC client library does not.
- **Exactly one retry:** see §4.

## Consequences

- JobSeeker no longer loses its users' sessions in a slow moment, once its Organization's window is set (a 10 to 30 s value covers a typical read timeout).
- Documented limit, unchanged: access tokens already issued live until they expire.
- `PUT /api/v1/admin/organizations/{id}/session-policy` gains `refreshTokenReuseGraceSeconds`; a client that omits it sets it to 0, as it replaces the whole policy.

## Tests

`RotateRefreshTokenServiceTest` (inside the window, the boundary to the nanosecond, window off, before the window, successor used, lost race for the successor, repeated retries, Session policy, scopes, sign-out, suspended Account; the boundary test fails if the comparison is mutated), `RefreshTokenRotationIntegrationTest` (a lost response keeps the session and retires the lost token; a used successor still ends the whole chain), and the policy's domain, validator, controller and API tests.
