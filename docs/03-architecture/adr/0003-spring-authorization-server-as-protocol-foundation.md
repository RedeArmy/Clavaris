# ADR-0003: Spring Authorization Server as the OIDC/OAuth2 protocol foundation

**Status:** ✅ Aprobado

## Context

Given the decision to build rather than adopt a finished IdP (ADR-0001), the OAuth2/OIDC protocol layer itself (Authorization Code flow, PKCE validation, token endpoint semantics, JWKS publication, discovery document) still needs to be implemented correctly — this is the part of "building an identity provider" that is genuinely dangerous to get wrong by hand, distinct from the product layer (accounts, organizations, admin surface) that is this project's actual differentiator.

## Decision

Use **Spring Authorization Server** (Spring's official OAuth2/OIDC provider framework) as the protocol-compliance foundation. Clavaris's own code implements the product layer — `Account`, `Organization`, `OAuthClient` domain models, the hosted login/consent UI, email flows — on top of it, never reimplementing the protocol state machine or JWT signing by hand.

## Consequences

- **Positive:** protocol correctness (PKCE validation, code exchange, token introspection semantics) is inherited from a maintained, widely-used library instead of hand-built and hand-tested.
- **Positive:** natural fit with the rest of the stack (Java 25, Spring Boot 4.1, Spring Security) — no additional runtime or language introduced.
- **Negative:** Clavaris is coupled to Spring Authorization Server's release cadence and design opinions; if the project is abandoned or diverges incompatibly from Spring Boot's own versioning, this becomes a forced migration. No fallback plan exists yet (`project-charter.md` §6 — flagged as an unmitigated assumption).
- **Negative:** some product requirements (e.g. the exact shape of `organization-module`) may not map cleanly onto Spring Authorization Server's extension points, requiring workarounds discovered only during implementation — acceptable risk, not fully known yet.

## Alternatives considered

- **Hand-rolled OAuth2/OIDC implementation** — rejected outright: this is precisely the kind of security-critical protocol code that should never be written from scratch when a vetted alternative exists.
- **Adopting a finished IdP product** — see ADR-0001 (different decision layer: whether to build at all, not what to build on top of).

## Addendum — re-evaluated against ADR-0010, still holds, one risk elevated to a required spike (added 2026-08-17)

ADR-0010 (organization-scoped tenant isolation) introduced a requirement this ADR's original text did not anticipate: a per-`Organization` issuer and JWKS, not a single Clavaris-wide one (ADR-0010 §5). This ADR was re-evaluated against that requirement, including a serious look at Keycloak — whose "realm" concept is a closer off-the-shelf match to ADR-0010's isolation model than Spring Authorization Server's default single-issuer shape.

**Re-confirmed: stay on Spring Authorization Server.** Keycloak was **not** adopted, for the same reasons as ADR-0001 plus one new one: `vision-document.md` §7 (long-term intent to take Clavaris to market as its own product) makes owning the full codebase — not extending another product's admin console and data model via its SPI — more valuable, not less, than it was when ADR-0001 was first written. Building the differentiator (the tenant-isolation model itself) as Clavaris's own code is more defensible as a product than as a configured deployment of someone else's.

**What actually changed: the "some product requirements may not map cleanly onto Spring Authorization Server's extension points" consequence (above) is no longer a vague, low-priority risk — it is now the single most important unvalidated assumption in this ADR.** Spring Authorization Server's multi-issuer support is real and documented (issuer resolution via `AuthorizationServerContext`, resolved per request), but per-tenant **JWKS** (a distinct `JWKSource` per resolved issuer, correctly wired into the auto-configured `/oauth2/jwks` endpoint and `NimbusJwtEncoder`) is an extension pattern built on top of that hook, not a first-class configuration option — nobody has verified it works cleanly against Spring Boot 3.4's autoconfiguration rather than requiring large parts of it to be replaced.

**Priority raised (2026-08-17):** ADR-0010 was updated with an explicit scope decision to build full per-Organization isolation *in v1*, not defer it — there is no longer a "ship a simpler shared-issuer v1 now, add per-tenant isolation later" fallback if the spike below turned out harder than expected, because that later addition would itself be the exact breaking migration ADR-0010's decision exists to avoid. This made the spike a blocking prerequisite for the whole v1 timeline, not an isolated technical validation.

## Spike results — GO, with two required implementation patterns (2026-08-17)

Full spike report, methodology, and evidence: `docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md`. Summary below.

The spike ran: a throwaway two-tenant Spring Boot 3.4.1 + Spring Authorization Server 1.4.1 app (`org-a`, `org-b`, `client_credentials` grant, never committed to this repo — findings only). All three claims were verified with real HTTP calls and real RSA signature verification, not just "it compiled":

1. **Discovery**: `GET /o/{org}/.well-known/openid-configuration` returned the correct, isolated `issuer` and endpoint URLs per organization.
2. **JWKS isolation**: `/o/org-a/oauth2/jwks` and `/o/org-b/oauth2/jwks` served distinct keys (`kid: org-a-key-1` vs `org-b-key-1`). A token issued by org-a decoded to `iss: http://.../o/org-a`, its signature verified cryptographically (RSA/SHA-256) against org-a's own published key, and **org-b's JWKS had no matching `kid` at all** — cross-tenant verification is structurally impossible, not just policy-disallowed.
3. **Client-registry isolation**: a client registered only under org-a's `RegisteredClientRepository`, presenting valid credentials against org-b's own token endpoint, got `401 invalid_client` — confirmed with a real request, not inferred from config.

**Verdict: GO.** Spring Authorization Server supports this cleanly via its own public extension points — no autoconfiguration was replaced wholesale. But "clean" required two non-obvious patterns, both real findings worth keeping as implementation guidance (not just "it works, don't worry about how"):

- **The OIDC discovery endpoint's path is not configurable per tenant** — unlike `tokenEndpoint`/`jwkSetEndpoint`/`authorizationEndpoint` (all literal, settable per `AuthorizationServerSettings`, exact-matched by their own filters, and this worked immediately for a fully custom `/o/{org}/oauth2/...` path with zero extra code), `OidcProviderConfigurationEndpointFilter` is hardcoded to `/.well-known/openid-configuration`, or `/**/.well-known/openid-configuration` under a `multipleIssuersAllowed(true)` flag that **cannot** be combined with an explicit fixed `issuer(String)` (`AuthorizationServerSettings.Builder.build()` throws `IllegalArgumentException` if both are set) — that flag's design assumes the issuer is derived dynamically per-request (host/forwarded-headers), which fits a reverse-proxy-per-tenant topology, not this project's single-deployable model. **Fix, small and self-contained:** a ~40-line filter per tenant chain, registered at the same insertion point (`addFilterBefore(..., AbstractPreAuthenticatedProcessingFilter.class)`) SAS's own configurer uses, built entirely from SAS's own public `OidcProviderConfiguration.Builder` and `OidcProviderConfigurationHttpMessageConverter` — it doesn't reimplement OIDC discovery, it just places SAS's own object at the path SAS's own DSL has no hook to configure.
- **The JWKS endpoint does not automatically use whatever key backs the token signer** — `OAuth2AuthorizationServerConfigurer.tokenGenerator(...)` controls *signing*, but `NimbusJwkSetEndpointFilter` (the public `/oauth2/jwks` endpoint) resolves its key source independently via `HttpSecurity`'s own shared-object registry (`httpSecurity.getSharedObject(JWKSource.class)`), falling back to a single ApplicationContext-wide bean if unset. Missed on the first implementation attempt during this spike: tokens signed correctly with a per-tenant key, while the public JWKS endpoint silently served Spring Boot's autoconfigured *default* key for both tenants — a verifier would have failed to validate every token issued by either tenant, and nothing in the code would have looked wrong without live-testing the actual signature end-to-end. **Fix:** build the tenant's `JWKSource` once, pass the same instance to both the `JwtEncoder` *and* `httpSecurity.setSharedObject(JWKSource.class, ...)` before applying the authorization server configurer.

**Consequence for implementation:** both patterns above are small, targeted, and use only SAS's own public API — this is "clean extension," matching the bar for staying on Spring Authorization Server. Neither required touching SAS's internals or disabling its autoconfiguration. The `RegisteredClientRepository`/login-`AuthenticationProvider` tenant-scoping claim (item 3 in the original spike plan) was validated for the client-credentials grant via `RegisteredClientRepository`; the interactive-login `AuthenticationProvider` case was not separately spiked — it's standard Spring Security territory (a `UserDetailsService`/`AuthenticationProvider` filtered by a resolved tenant ID) with materially lower risk than the two findings above, and is deferred to the actual `identity-module` login use case rather than the spike.

**Not yet answered by this spike** (real implementation work, not spike scope): dynamic per-organization-ID resolution from a database instead of two hardcoded orgs; automated key generation/storage (this spike generated an in-memory RSA key pair at startup, not `TOKEN_SIGNING_KEY_STORE_PATH`-backed persistent keys per ADR-0002); the interactive Authorization Code + login flow end-to-end (only `client_credentials` was exercised). These are real `identity-module`/`client-registry-module` use-case work, next.
