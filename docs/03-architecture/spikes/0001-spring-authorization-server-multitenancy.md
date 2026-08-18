# Spike Report — Per-Organization Issuer, JWKS, and Client Registry on Spring Authorization Server

| | |
|---|---|
| **Status** | ✅ Completed — **GO** |
| **Date** | 2026-08-17 |
| **Author** | Engineering (solo project) |
| **Time-box** | 2–3 days allotted (`ADR-0003` addendum); completed within a single investigation session |
| **Related decisions** | `ADR-0003` (Spring Authorization Server as protocol foundation), `ADR-0010` (organization-scoped tenant isolation, §5) |
| **Code** | Throwaway, not committed to this repository — findings only (see Methodology) |

## TL;DR

Spring Authorization Server (SAS) 1.4.1 on Spring Boot 3.4.1 supports a per-`Organization` issuer, a per-`Organization` JWKS document, and a per-`Organization` `RegisteredClientRepository` as a **clean extension** of its public API — no autoconfiguration was disabled or replaced wholesale. Two non-obvious implementation patterns are required to get there, both discovered by running real code against real HTTP requests, not by reading documentation. Full details in [Findings](#findings). **Recommendation: proceed with `ADR-0010` §5 as designed; no change to `ADR-0003`.**

## 1. Problem Statement

`ADR-0010` (organization-scoped tenant isolation) requires every `Organization` to have its own OIDC issuer, its own JWKS document, and its own isolated `OAuthClient` registry — the mechanism that makes cross-tenant token forgery structurally impossible rather than merely policy-disallowed. `ADR-0003` committed Clavaris to Spring Authorization Server as the OIDC/OAuth2 protocol foundation *before* this multi-tenant requirement existed. Nobody had verified whether SAS's extension points actually support this shape, or whether achieving it would require fighting — or replacing — significant parts of its autoconfiguration, which would have been grounds to reopen `ADR-0003` (see that ADR's own design-review discussion of Keycloak as the closest off-the-shelf alternative).

Continuing to design and document `identity-module`/`client-registry-module` against an unverified assumption was flagged as the single largest unresolved risk in the project across three prior design-review passes. This spike exists to close that gap with evidence.

## 2. Objective

Answer three specific questions, empirically:

1. Does per-`Organization` issuer resolution (`{clavarisBaseUrl}/o/{organizationId}`) work against Spring Boot 3.4's autoconfiguration without wholesale replacement?
2. Does a custom `JWKSource` resolving keys by tenant plug cleanly into the auto-configured JWKS endpoint and token encoder?
3. Can `RegisteredClientRepository` be tenant-scoped without context leaking across concurrent requests?

## 3. Non-Goals

Explicitly out of scope for this investigation — these are real `identity-module`/`client-registry-module` implementation work, not spike scope, and are listed here so their absence isn't mistaken for "not needed":

- Dynamic organization resolution from a database (this spike hardcodes two organizations, `org-a` and `org-b`).
- Persistent signing-key storage (`TOKEN_SIGNING_KEY_STORE_PATH`, `ADR-0002`) — the spike generates an in-memory RSA key pair at process startup.
- The interactive Authorization Code + login flow (only the `client_credentials` grant was exercised — sufficient to validate issuer/JWKS/client-registry isolation, the three highest-risk claims; the login `AuthenticationProvider` tenant-scoping is standard Spring Security territory with materially lower risk, deferred to the real use case).
- Key rotation (`ADR-0010` §5.2).
- Rate limiting (`ADR-0010` §6).
- Production-grade secret handling — the spike's client secret uses `{noop}` encoding, never acceptable outside a throwaway spike (`ADR-0005`).

## 4. Methodology

A standalone, disposable Spring Boot application was built outside this repository (never committed — Clavaris's `identity-module`/`client-registry-module` remain at zero application classes, per `CLAUDE.md` §11; mixing exploratory spike code into a security-critical codebase was treated as unacceptable regardless of outcome). Findings were written back into `ADR-0003`/`ADR-0010`; the code itself was discarded after the investigation concluded.

**Stack:** Java 21, Spring Boot 3.4.1, `spring-boot-starter-oauth2-authorization-server` (resolves `spring-security-oauth2-authorization-server` 1.4.1, `spring-security-core` 6.4.2), `spring-boot-starter-web`.

**Topology:** two hardcoded tenants, `org-a` and `org-b`, each configured as an independent `SecurityFilterChain` bean (`@Order(1)`/`@Order(2)`, `securityMatcher("/o/{org}/**")`), each with:
- Its own `AuthorizationServerSettings`, explicit fixed issuer `http://127.0.0.1:8080/o/{org}`, explicit endpoint paths under that prefix.
- Its own RSA-2048 key pair, generated at startup, `kid = "{org}-key-1"`.
- Its own `InMemoryRegisteredClientRepository`, containing exactly one `client_credentials` client scoped to that org only.

**Verification approach:** every claim was checked against a live, running instance via `curl` and Python (`urllib`, `cryptography`), not inferred from configuration or framework source reading alone. Where source reading *was* used (see §5.2, §5.3), it was to explain and fix a behavior already observed empirically, then the fix was re-verified live.

## 5. Findings

### 5.1 Per-tenant issuer, token, authorization, and revocation endpoints — worked immediately

`AuthorizationServerSettings.Builder` exposes `issuer(String)`, `authorizationEndpoint(String)`, `tokenEndpoint(String)`, `jwkSetEndpoint(String)`, `tokenRevocationEndpoint(String)`, `tokenIntrospectionEndpoint(String)` as independently settable, arbitrary literal paths. Internally, `OAuth2TokenEndpointFilter`, `NimbusJwkSetEndpointFilter`, and `OAuth2AuthorizationEndpointFilter` each build an exact `AntPathRequestMatcher` directly from whatever string is configured — no assumption of a fixed relative shape. Setting these to `/o/org-a/oauth2/token`, `/o/org-a/oauth2/jwks`, etc. per chain worked on the first attempt, with zero custom code:

```
POST /o/org-a/oauth2/token   -> 400 (route live, malformed test request)
GET  /o/org-a/oauth2/jwks    -> 200
```

**Conclusion:** this is first-class, documented-by-construction behavior. No further discussion needed.

### 5.2 OIDC discovery endpoint — path is not tenant-configurable; required a small custom filter

The first end-to-end request revealed a gap: `GET /o/org-a/.well-known/openid-configuration` returned `404`, while every other endpoint above worked.

**Root cause (confirmed by reading `OidcProviderConfigurationEndpointFilter` and `AuthorizationServerSettings` source, both bundled sources jars pulled locally):** unlike the endpoints in §5.1, the discovery filter's path is **not** a setting. It is hardcoded:

```java
private static final String DEFAULT_OIDC_PROVIDER_CONFIGURATION_ENDPOINT_URI = "/.well-known/openid-configuration";
```

A second mode exists — `AuthorizationServerSettings.builder().multipleIssuersAllowed(true)` — under which the filter's matcher becomes `/**/.well-known/openid-configuration` (any prefix). This looked initially like the answer. It is not usable for this project's topology:

```java
// AuthorizationServerSettings.Builder.build()
if (authorizationServerSettings.getIssuer() != null && authorizationServerSettings.isMultipleIssuersAllowed()) {
    throw new IllegalArgumentException("The issuer identifier (" + ... + ") cannot be set when isMultipleIssuersAllowed() is true.");
}
```

`multipleIssuersAllowed(true)` **requires** the issuer to be left unset and resolved dynamically per-request (`AuthorizationServerContextFilter.IssuerResolver`, confirmed by source reading: it derives the issuer from `scheme://host:port` + whatever remains of the request path after stripping a matched endpoint suffix). This is SAS's officially documented pattern for path-based multi-tenancy — but it's designed for a **reverse-proxy-per-tenant topology**, where an upstream proxy strips the tenant prefix before the request reaches Spring and reconstructs it via forwarded headers for issuer purposes. That is a materially different deployment shape than `ADR-0010`'s single-deployable model, and was not adopted for that reason.

**Fix:** a ~40-line custom `OncePerRequestFilter`, registered at the same insertion point SAS's own configurer uses (`addFilterBefore(filter, AbstractPreAuthenticatedProcessingFilter.class)`), matching the exact tenant-scoped path and building the response from SAS's own public `OidcProviderConfiguration.Builder` and `OidcProviderConfigurationHttpMessageConverter` — see [Appendix A](#appendix-a-custom-discovery-filter). This does not reimplement OIDC discovery; it places SAS's own model object at a path SAS's own DSL has no hook to configure.

**Verified after the fix:**

```json
GET /o/org-a/.well-known/openid-configuration
{
  "issuer": "http://127.0.0.1:8080/o/org-a",
  "token_endpoint": "http://127.0.0.1:8080/o/org-a/oauth2/token",
  "jwks_uri": "http://127.0.0.1:8080/o/org-a/oauth2/jwks",
  ...
}
GET /o/org-b/.well-known/openid-configuration
{
  "issuer": "http://127.0.0.1:8080/o/org-b",
  ...
}
```

(A first pass of the fix concatenated `issuer + settings.getEndpoint()` and produced doubled path segments, e.g. `/o/org-a/o/org-a/oauth2/token` — because the endpoint settings already carried the full tenant-prefixed path. Corrected to concatenate the bare `scheme://host:port` with the already-prefixed setting instead. Noted here because it's exactly the class of easy-to-miss bug this spike exists to surface before it ships.)

### 5.3 JWKS endpoint — does not automatically use the token signer's key; required explicit wiring

This is the most consequential finding. First implementation pass: tokens were requested and issued successfully, with what appeared to be correct per-tenant signing (`OAuth2AuthorizationServerConfigurer.tokenGenerator(...)` was given a `JwtGenerator` wrapping a per-tenant `NimbusJwtEncoder`). Nothing in the token issuance path indicated a problem.

**The bug surfaced only when the JWKS endpoint's actual content was inspected:**

```
GET /o/org-a/oauth2/jwks -> kid: 57c36910-073c-4795-a5c5-bfc443bf676a
GET /o/org-b/oauth2/jwks -> kid: 57c36910-073c-4795-a5c5-bfc443bf676a   (identical!)
```

Both tenants' public JWKS endpoints served **the same key** — and it matched neither tenant's actual signing key. A verifier fetching either tenant's JWKS and attempting to validate a real issued token would have failed for every single token, for both tenants, with no error anywhere in the issuance path itself.

**Root cause (source reading):** `.tokenGenerator(...)` only wires the *signer*. `NimbusJwkSetEndpointFilter` — the filter backing the public `/oauth2/jwks` endpoint — resolves its `JWKSource` independently, via `OAuth2ConfigurerUtils.getJwkSource(HttpSecurity)`, which checks `httpSecurity.getSharedObject(JWKSource.class)` first and falls back to a single `JWKSource` bean from the `ApplicationContext` (Spring Boot's zero-config-dev-convenience autoconfigured default) if nothing was explicitly set. Nothing had been explicitly set on either chain's `HttpSecurity`, so both chains silently fell through to the same shared fallback.

**Fix:** build the tenant's `JWKSource` exactly once, pass the *same instance* to both the signer and the shared-object slot the JWKS filter actually reads from:

```java
JWKSource<SecurityContext> tenantJwks = tenantJwkSource(org);
http.setSharedObject(JWKSource.class, tenantJwks);
JwtEncoder tenantJwtEncoder = new NimbusJwtEncoder(tenantJwks);
```

**Verified after the fix, end-to-end, cryptographically — not by status code:**

```
$ curl -u client-org-a:secret-org-a -d "grant_type=client_credentials&scope=spike.read" \
       http://127.0.0.1:8080/o/org-a/oauth2/token
# -> access_token issued

Decoded token header:  {"kid": "org-a-key-1", "alg": "RS256"}
Decoded token payload: {"iss": "http://127.0.0.1:8080/o/org-a", ...}

GET /o/org-a/oauth2/jwks -> kids: ["org-a-key-1"]
GET /o/org-b/oauth2/jwks -> kids: ["org-b-key-1"]

Signature verification (RSA/PKCS1v15/SHA-256) against org-a's published public key: VALID
Attempted verification against org-b's JWKS: NO matching kid — verification impossible, not merely rejected
```

**Conclusion:** cross-tenant token forgery is structurally impossible, not policy-disallowed — the exact guarantee `ADR-0010` §5 requires. This is the strongest evidence produced by the spike.

### 5.4 `RegisteredClientRepository` tenant isolation — worked as designed, verified with a real cross-tenant attempt

Each chain was given its own `InMemoryRegisteredClientRepository` containing exactly one client. A client registered only under `org-a` was used to attempt a token request against `org-b`'s token endpoint with valid Basic-Auth credentials:

```
$ curl -u client-org-a:secret-org-a -d "grant_type=client_credentials&scope=spike.read" \
       http://127.0.0.1:8080/o/org-b/oauth2/token
HTTP 401
{"error":"invalid_client"}
```

No custom code was required for this — `OAuth2AuthorizationServerConfigurer.registeredClientRepository(...)` scopes cleanly per chain, exactly as the public API implies. Verified, not assumed.

## 6. Risks and Limitations Identified

- The two custom-code patterns above (§5.2, §5.3) are small and self-contained, but they are still project-owned code sitting on top of a security-critical protocol surface — both need their own dedicated tests when implemented for real (`test-strategy.md` §3's "security-specific" test tier), not just happy-path coverage.
- §5.3's failure mode (silent, cryptographically undetectable without explicitly checking the JWKS content against a real signature) is the kind of bug that a naive test suite — one that only checks "token issuance returns 200" — would never catch. Any real implementation's test suite must assert actual signature verification against the published JWKS, per tenant, not just endpoint reachability.
- This spike used two *hardcoded* tenants. Resolving the tenant per request from a database lookup (the real `identity-module` shape) introduces its own concerns — connection-pool-per-request lookup cost, caching, and correct behavior when an `Organization` is looked up mid-request-processing — not evaluated here.
- No load/concurrency testing was performed beyond sequential manual requests. `AuthorizationServerContextHolder`'s thread-local-based context propagation under real concurrent load was not stressed.

## 7. Recommendation

**GO.** Proceed with `ADR-0010` §5 as designed. Do not reopen `ADR-0003`. The two implementation patterns in §5.2 and §5.3 should be used directly as the starting point for `identity-module`'s/`client-registry-module`'s first real OIDC use case, rather than re-derived from scratch — see [Appendix A](#appendix-a-custom-discovery-filter) and [Appendix B](#appendix-b-jwks-source-wiring).

## 8. Follow-Up Work (real implementation, not spike scope)

1. Replace the two hardcoded tenants with database-backed `Organization` resolution (blocked on the still-open "Organization provisioning" question, `ADR-0010`'s own open questions).
2. Persistent, `TOKEN_SIGNING_KEY_STORE_PATH`-backed key material instead of an in-memory-generated key (`ADR-0002`).
3. Manual key-rotation admin endpoint (`ADR-0010` §5.2), built on the same `JWKSource`-wiring pattern from §5.3 — rotation means swapping the instance behind that shared object with overlap, not just generating a new key.
4. Exercise the interactive Authorization Code + PKCE + login flow end-to-end, not just `client_credentials`.
5. Dedicated security-specific tests for both custom-code patterns (§6).

---

## Appendix A: Custom Discovery Filter

```java
// Serves OIDC discovery at an explicit, tenant-scoped path. Reuses SAS's own
// OidcProviderConfiguration.Builder + OidcProviderConfigurationHttpMessageConverter —
// does not reimplement the OIDC discovery document format.
final class TenantScopedOidcDiscoveryFilter extends OncePerRequestFilter {
    private final AntPathRequestMatcher matcher;
    private final String issuer;
    private final String baseUrl; // scheme+host+port only; settings already carry the full tenant-prefixed path
    private final AuthorizationServerSettings settings;
    private final OidcProviderConfigurationHttpMessageConverter converter =
            new OidcProviderConfigurationHttpMessageConverter();

    TenantScopedOidcDiscoveryFilter(String path, String baseUrl, AuthorizationServerSettings settings) {
        this.matcher = new AntPathRequestMatcher(path, "GET");
        this.issuer = settings.getIssuer();
        this.baseUrl = baseUrl;
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matcher.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        OidcProviderConfiguration configuration = OidcProviderConfiguration.builder()
                .issuer(issuer)
                .authorizationEndpoint(baseUrl + settings.getAuthorizationEndpoint())
                .tokenEndpoint(baseUrl + settings.getTokenEndpoint())
                .tokenEndpointAuthenticationMethods(m -> m.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()))
                .jwkSetUrl(baseUrl + settings.getJwkSetEndpoint())
                .userInfoEndpoint(baseUrl + settings.getOidcUserInfoEndpoint())
                .responseType(OAuth2AuthorizationResponseType.CODE.getValue())
                .grantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
                .grantType(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
                .grantType(AuthorizationGrantType.REFRESH_TOKEN.getValue())
                .tokenRevocationEndpoint(baseUrl + settings.getTokenRevocationEndpoint())
                .codeChallengeMethod("S256")
                .subjectType("public")
                .idTokenSigningAlgorithm(SignatureAlgorithm.RS256.getName())
                .scope(OidcScopes.OPENID)
                .build();
        converter.write(configuration, MediaType.APPLICATION_JSON, new ServletServerHttpResponse(response));
    }
}

// wired per tenant chain, same insertion point SAS's own OidcConfigurer uses:
http.addFilterBefore(
        new TenantScopedOidcDiscoveryFilter(prefix + "/.well-known/openid-configuration", baseUrl, settings),
        AbstractPreAuthenticatedProcessingFilter.class);
```

## Appendix B: JWKSource Wiring

```java
// One JWKSource instance per tenant, shared between the signer and the
// endpoint that publishes it — this is the fix for §5.3.
JWKSource<SecurityContext> tenantJwks = tenantJwkSource(org); // generates/loads this org's RSA key
http.setSharedObject(JWKSource.class, tenantJwks);             // what NimbusJwkSetEndpointFilter actually reads

JwtEncoder tenantJwtEncoder = new NimbusJwtEncoder(tenantJwks); // what actually signs tokens
OAuth2TokenGenerator<?> tokenGenerator = new JwtGenerator(tenantJwtEncoder);

http.securityMatcher(prefix + "/**")
    .with(OAuth2AuthorizationServerConfigurer.authorizationServer(), server -> server
        .registeredClientRepository(tenantClientRepository(org))
        .authorizationServerSettings(settings)
        .tokenGenerator(tokenGenerator)
    );
```
