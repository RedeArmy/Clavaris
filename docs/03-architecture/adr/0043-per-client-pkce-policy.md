# ADR-0043: PKCE as a per-client policy, strict by default, never optional for a public client

**Status:** 🟡 Pending review (2026-11-14)

## Context

BR-CLIENT-03 read: the Authorization Code flow requires PKCE for **every** client, confidential ones included. It was enforced by one fixed line, `requireProofKey(true)`, in `OrganizationRegisteredClientRepository`. That is stricter than the standards and stricter than every product Clavaris is compared with, and it has three costs that were measured, not assumed:

- **Standards.** OIDC Core 1.0 does not mention PKCE. RFC 9700 (BCP 240, January 2025) says public clients MUST use it and confidential clients SHOULD (it is *recommended*); the authorization server MUST support it and MUST refuse a `code_verifier` when the authorization request had no `code_challenge`. OAuth 2.1 is still a draft and reads stricter, but a draft is not a requirement.
- **Competition.** Okta's "Require PKCE as additional verification" is **off** by default on web apps; Auth0, Keycloak, Ory Hydra, Spring Authorization Server and Entra do not force it on a confidential client either. Everyone enforces it for public clients.
- **Interoperability and certification.** The v1 exit criterion is a clean run of the OpenID Foundation conformance suite. Its Basic OP plan authenticates a confidential client and sends **no PKCE**; run on 2026-11-13 against a test Organization, every module that needs an authorization code ended `invalid_request: code_challenge`. The same applies to the server-side OIDC libraries and off-the-shelf products (anything that is not written for OAuth 2.1) that the success metric, "a new project integrates with standard OIDC libraries in under a day", promises to serve.

What PKCE protects against is the interception or injection of an authorization code. For a confidential client that is already covered, in layers, by the client secret at the token endpoint, exact `redirect_uri` matching (BR-CLIENT-01), `state` and `nonce`, and a single-use, short-lived code that revokes what it issued if replayed (BR-CLIENT-03 was therefore defence in depth, as its own text says, and defence in depth has a price when the layer is mandatory).

## Decision

**PKCE is a per-client policy, `oauth_clients.pkce_policy`, with two values and a strict default.**

| Policy | Authorization request without a `code_challenge` | Request with a challenge |
|---|---|---|
| `REQUIRED` (the default, and what every client had until now) | refused, `invalid_request` | the verifier is mandatory at the token endpoint and checked |
| `OPTIONAL` | accepted | the verifier is mandatory at the token endpoint and checked, exactly as under `REQUIRED` |

1. **Secure by default, explicit opt-out**, the shape ADR-0013 and ADR-0017 already chose. The column is `NOT NULL DEFAULT 'REQUIRED'` with a `CHECK` on the two values, so every existing row, and any insert that does not say, stays strict. The API accepts an optional `pkcePolicy` on registration and resolves an absent field to `REQUIRED` at the boundary; a value that is neither is a 400.
2. **Never optional for a public client.** A client with no secret has nothing but PKCE to bind the code to the party that asked for it. `PkcePolicy.requireAllowedFor(confidential, policy)` refuses `OPTIONAL` for one, and `OAuthClient` applies it in its constructor, so no path (a registration request, a later update, a future bulk import) can build one. Every client is confidential today (ADR-0013), so the rule cannot fire yet; it is what keeps TD-FUT-071 (public clients) from registering the first one weaker than its own protocol allows. A public client will have PKCE required and not configurable, in its own ADR.
3. **Choosing `OPTIONAL` removes a requirement, never a check on what was sent.** The risk worth checking was the downgrade RFC 9700 4.8 names: if Spring Authorization Server ignored a verifier when the code had no challenge, a code issued without one could be presented with any verifier and the exchange would go ahead as if PKCE had been in force. It does not: while it authenticates the client (`ClientSecretAuthenticationProvider`, for both `client_secret_basic` and `client_secret_post`) it refuses a `code_verifier` for a code whose authorization request had no `code_challenge`. This was a first design question, and a guard of our own was written for it and then removed once the integration test showed the library already answered, before any code of ours ran. No code was added for it; `ClientPkcePolicyIntegrationTest` exists so that stays true.
4. **The change is a deliberate, audited act of the client's owner.** Moving to `OPTIONAL` is its own button in the dashboard's client page, behind a confirmation; moving back to `REQUIRED` is direct, because it only adds a requirement. The form posts the value by name and the server falls back to `REQUIRED` when it is missing, so a blank or forged request can only land on the strict side. The use case writes `oauth_client.pkce_policy_updated` (tone *sensitive*) with `from=` and `to=`, so the log shows which way it went without opening the client; asking for the policy a client already has writes nothing. A client *registered* as `OPTIONAL` says so on its `oauth_client.registered` line; the strict default adds nothing to it.
5. **What stays as it was:** the discovery document (`code_challenge_methods_supported` is still `["S256"]`, and `plain` is refused whatever the policy), the S256 requirement on any challenge that is sent, the platform tier (its clients use `client_credentials`), and every client's consent setting.

## Consequences

- **Positive:** the OpenID Foundation Basic OP plan, and any confidential client whose library cannot send PKCE, can integrate against a client its owner has set to `OPTIONAL`, with nothing else in the server relaxed. The conformance run uses a client in a development Organization; production Organizations stay strict until their owner decides otherwise.
- **Positive:** no behaviour changes on deploy. Every existing client is `REQUIRED`.
- **Positive:** the downgrade case RFC 9700 names is shown to be closed, by the library, and a test keeps it closed.
- **Negative:** one more per-client setting to understand. Mitigated by the strict default, a confirmation that says what is being given up, and the audit event.
- **Negative:** an `OPTIONAL` client loses the one defence against code interception that does not depend on the secret. The residual risk is a code leaked together with a compromised client secret; the compensating controls are the secret itself, exact redirect matching, a short single-use code, and the refusal of a verifier with no challenge. A requirement of `nonce` is **not** one of them: the conformance suite's own `oidcc-ensure-request-without-nonce-succeeds-for-code-flow` module requires a server to accept a code-flow request without it.
- **Open:** whether production Organizations may use `OPTIONAL` from the first day or only after telemetry shows what it costs. This ADR allows it, behind the confirmation and the audit event. A counter of authorization requests by `pkce=present|absent` would show which optional clients could go back to `REQUIRED`, and is a follow-up, not part of this change.

## Alternatives considered

- **A global switch.** Rejected: affects every tenant, cannot be audited per client, and is the wrong shape for a multi-tenant system (the argument ADR-0017 made for consent).
- **A per-Organization policy that its clients inherit.** Not rejected, deferred: it would be a default for the per-client value, which still has to exist. Worth building once the per-client setting has a month of use to show whether Organizations want a single switch.
- **Keep BR-CLIENT-03 as it was and register a special client only for the conformance run.** The cheapest way to certify, and it leaves the interoperability cost for every real integrator in place. Rejected as the whole answer; the conformance run itself is exactly this client.
- **Make PKCE optional for public clients too.** Rejected without a deadline: RFC 9700 forbids it, no product Clavaris is compared with allows it, and for a client with no secret it removes the only protection. Not a way to compete; a way to be unsafe.
- **Require `nonce` when PKCE is optional, as the compensating control.** Rejected: the conformance suite requires a server to accept a request without one.
- **An authentication provider of our own that refuses a verifier sent without a challenge.** Written first, on the assumption that Spring ignored it, and removed when the integration test showed Spring already refuses it. Extra code, and a database lookup on every exchange of an optional client, for a check that was already made.

## Verification

Unit tests for the domain rule, the use case (both directions audited, the no-op unaudited), the dashboard endpoint (an absent or invalid value, every error mapping, the page showing the right form for each state) and the registration boundary (an absent value is the strict default, anything but the two values is a 400). An integration test against real Postgres and Redis covers each cell of the table above: a default client refuses no challenge, an optional one completes the whole flow with none, a wrong verifier or a missing one on a challenged optional request is `invalid_grant`, a verifier on a challengeless code is refused (with the secret in the header and in the body), and `plain` is refused. The OpenID Foundation Basic OP plan is then run against an optional client.

Related: BR-CLIENT-03 (rewritten), ADR-0013, ADR-0017, TD-FUT-071, `openid-conformance-plan.md`, RFC 7636, RFC 9700, draft-ietf-oauth-v2-1.
