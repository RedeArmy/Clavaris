# ADR-0013: Confidential OAuth clients only in v1 — no public/SPA client type, no CORS policy

**Status:** ✅ Aprobado (2026-08-24)

## Context

`technical-debt-register.md` TD-FUT-015 (SDE-III review, 2026-08-24) surfaced a real, previously-undecided integration question: does Clavaris support a browser-based **public** OAuth client — a consumer's frontend calling `/oauth2/token` directly from browser JavaScript, with no client secret (relying on PKCE alone) — or only a **confidential** client, where the token exchange always happens server-side in the consumer's own backend? The distinction matters because a public client requires a real CORS policy (an explicit, per-origin `Access-Control-Allow-Origin` allowlist on the token endpoint) and Clavaris has none — zero `CorsConfiguration`/`@CrossOrigin`/`Access-Control-*` handling exists anywhere in the codebase.

Investigating before deciding (not assuming either way) found the answer was already locked in by construction, just never stated as a deliberate decision:

- `oauth_clients.client_secret_hash` is `NOT NULL` at the database level (`V20260819170000__create_oauth_clients_table.sql`) — there is no schema shape for a client with no secret.
- `OAuthClient.register(...)` (domain model) requires a non-blank `clientSecretHash` — rejects construction otherwise.
- `RegisterOAuthClientService.handle(...)` always generates a fresh 256-bit secret server-side and always hashes it before persisting — no field exists anywhere in `RegisterOAuthClientCommand` to request a secret-less registration.
- `OrganizationRegisteredClientRepository.toRegisteredClient(...)` hardcodes `.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)` on every `RegisteredClient` it builds — there is no code path anywhere that could ever produce `ClientAuthenticationMethod.NONE` (the SAS constant for a public client).
- `integration-design.md` §1's own sequence diagram already draws the token exchange (`POST /oauth2/token`) as a call from "Consumer app (e.g. JobSeeker)" — a distinct participant from "End user (browser)" — never from the browser directly.
- `CLAUDE.md` §3 already states the ecosystem this project was extracted from has a "no-SPA philosophy," consistent with every consumer (JobSeeker included) owning a real backend rather than being a backend-less SPA.

Every layer that would need to change to support a public client — the DB schema, the domain model, the use case, and the SAS wiring — independently already assumes a confidential client. This was never a real 50/50 open question; it was an unstated conclusion the codebase had already reached, surfaced as "undecided" only because nothing said so out loud.

## Decision

**v1 supports confidential OAuth clients only.** Every `OAuthClient` Clavaris ever issues requires `client_secret_basic` authentication in addition to mandatory PKCE (BR-CLIENT-03) — never PKCE alone. A consumer's token exchange (`POST /oauth2/token` or `/o/{organizationId}/oauth2/token`) must always happen server-side, in the consumer's own backend, which is the only place a client secret can be held without exposing it to every visitor's browser. **No CORS policy is added for `/oauth2/token` or any other OIDC endpoint** — there is no legitimate cross-origin browser caller to support, so a policy would only ever be attack surface with no corresponding capability behind it.

This closes TD-FUT-015 as a decision, not a build item: the "opaque CORS error" failure mode that row named cannot be prevented by better CORS configuration (a browser CORS rejection is opaque by the browser's own security model, not a Clavaris response Clavaris controls) — it's prevented by there being no legitimate scenario where a consumer's frontend would make that call directly at all. A consumer's frontend developer attempting it is doing something Clavaris was never designed to support, and this ADR plus the updated `integration-design.md`/`api-contract-overview.md` (§2 each) are the documentation that should stop them before they try, not a server-side error message after they do.

A defense-in-depth regression test (`OrganizationRegisteredClientRepositoryTest`) asserts every `RegisteredClient` this repository ever returns carries `ClientAuthenticationMethod.CLIENT_SECRET_BASIC` — proving the invariant this ADR relies on stays true if the wiring ever changes, not just documenting that it's true today.

## Consequences

- **Positive:** zero CORS attack surface — no origin-allowlist to misconfigure, no risk of an overly permissive `Access-Control-Allow-Origin` leaking a token exchange to an unintended origin, a real and common OAuth2 misconfiguration class in public-client deployments.
- **Positive:** simpler security model overall — every client that can complete a token exchange is, by construction, one that can also keep a secret confidential; PKCE remains mandatory on top (BR-CLIENT-03) as defense against authorization-code interception, not as the sole line of defense the way it has to be for a public client.
- **Positive:** matches the reference integration pattern `integration-design.md` §3 already documents for JobSeeker (a real backend `auth-module` acting as the OIDC relying party, never a backend-less frontend) — this ADR generalizes what was already true for the one real consumer into an explicit rule for every future one.
- **Negative:** a future consumer whose architecture is genuinely backend-less (a static SPA with no server component at all) cannot integrate with Clavaris as designed today — it would need to add a minimal backend-for-frontend (BFF) purely to hold the client secret and proxy the token exchange, or Clavaris would need a real v2 design effort (a distinct public-client `OAuthClient` type, a per-client registered-origin allowlist, real CORS wiring, and a security review of the resulting broadened attack surface) — not a small addition once needed.
- **Negative:** this is now a locked decision per `CLAUDE.md` §10's convention — revisiting it requires an explicit new ADR, not a quiet code change the day a public-client consumer shows up.

## Alternatives considered

- **Support public/SPA clients now, with a real per-origin CORS allowlist and a new secret-less `OAuthClient` registration path.** Rejected for v1: no current or near-term consumer needs it (JobSeeker's own `auth-module` is a real backend), it would require reworking four independent layers that all currently assume a secret exists, and it meaningfully broadens the attack surface (CORS misconfiguration, a weaker PKCE-only trust model) for a capability nothing yet exercises. Revisit if and when a genuinely backend-less consumer is a real, scheduled requirement — track as a v2 item if that happens, not spec work done speculatively now.
- **Leave the question formally undecided, document only that "it hasn't been decided yet."** Rejected: the codebase had already decided it in every layer that matters; refusing to say so out loud in the docs was the actual bug TD-FUT-015 found, not a genuine remaining ambiguity worth preserving.
