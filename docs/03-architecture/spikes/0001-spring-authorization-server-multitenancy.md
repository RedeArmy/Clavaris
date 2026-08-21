# Spike Report — Per-Organization Issuer, JWKS, and Client Registry on Spring Authorization Server

| | |
|---|---|
| **Status** | ✅ Completed — **GO**. See [Appendix C](#appendix-c-addendum--dynamic-single-chain-resolution-2026-08-19-sas-710) — the §6 dynamic-resolution gap is now closed and supersedes §5.2's `multipleIssuersAllowed` rejection. |
| **Date** | 2026-08-17 (original); addendum 2026-08-19 |
| **Author** | Engineering (solo project) |
| **Time-box** | 2–3 days allotted (`ADR-0003` addendum); completed within a single investigation session |
| **Related decisions** | `ADR-0003` (Spring Authorization Server as protocol foundation), `ADR-0010` (organization-scoped tenant isolation, §5) |
| **Code** | Throwaway, not committed to this repository — findings only (see Methodology) |

## TL;DR

Spring Authorization Server (SAS) 1.4.1 on Spring Boot 3.4.1 supports a per-`Organization` issuer, a per-`Organization` JWKS document, and a per-`Organization` `RegisteredClientRepository` as a **clean extension** of its public API — no autoconfiguration was disabled or replaced wholesale. Two non-obvious implementation patterns are required to get there, both discovered by running real code against real HTTP requests, not by reading documentation. Full details in [Findings](#findings). **Recommendation: proceed with `ADR-0010` §5 as designed; no change to `ADR-0003`.**

**2026-08-19 addendum:** the original investigation only validated two *static* hardcoded tenant chains and explicitly left dynamic, database-driven tenant resolution unevaluated (§6). By real-implementation time, Spring Boot 4.1 had bumped the resolved dependency to SAS 7.1.0, which turned out to support genuine single-chain dynamic multi-tenancy natively (`multipleIssuersAllowed`, decompiled and confirmed live against the actual resolved jar) — see [Appendix C](#appendix-c-addendum--dynamic-single-chain-resolution-2026-08-19-sas-710) for the full findings and the revised implementation shape for Task #19.

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

A standalone, disposable Spring Boot application was built outside this repository (never committed — Clavaris's `identity-module`/`client-registry-module` remain at zero application classes; mixing exploratory spike code into a security-critical codebase was treated as unacceptable regardless of outcome). Findings were written back into `ADR-0003`/`ADR-0010`; the code itself was discarded after the investigation concluded.

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

## Appendix C: Addendum — Dynamic Single-Chain Resolution (2026-08-19, SAS 7.1.0)

**Status:** ✅ Closes the §6 gap ("resolving the tenant per request from a database lookup... not evaluated here"). **Supersedes §5.2's rejection of `multipleIssuersAllowed`.**

This spike's original body (above) validated two *static*, hardcoded `SecurityFilterChain` beans (`org-a`/`org-b`), against Spring Boot 3.4.1 / SAS 1.4.1. It explicitly did not attempt a single dynamic chain resolving an arbitrary `{organizationId}` learned only at request time from the database — the real shape `identity-module` needs, since Organizations are created at runtime via `CreateOrganization`, not known at application-startup bean-wiring time.

By the time real implementation started, Spring Boot 4.1 had bumped the resolved dependency to **`spring-security-oauth2-authorization-server` 7.1.0** (folded into Spring Security's own versioning — a major jump from 1.4.1). Rather than re-run the original spike's black-box HTTP methodology, this addendum was produced by decompiling the actual resolved 7.1.0 jars (`javap -p -c -constants` against `spring-security-oauth2-authorization-server-7.1.0.jar` and `spring-security-config-7.1.0.jar`) — the same "confirmed live, not assumed" discipline this project applies everywhere, extended here to framework internals since no live multi-tenant instance existed yet to black-box test.

**Finding: `AuthorizationServerSettings.multipleIssuersAllowed(true)` is now genuine, framework-native, path-based multi-tenancy for a single deployable — not a reverse-proxy-only pattern.**

Three concrete, decompiled findings, each reversing or closing a §5.2/§5.3/§6 gap from the original spike:

1. **Endpoint matchers are now built with an official wildcard-prefix helper.** `OAuth2ConfigurerUtils.withMultipleIssuersPattern(String)` (new in 7.1.0) turns a relative endpoint path into `"/**" + path` whenever `multipleIssuersAllowed()` is true — e.g. `/oauth2/jwks` becomes the matcher pattern `/**/oauth2/jwks`, built via `PathPatternRequestMatcher`. This is wired automatically inside `OAuth2AuthorizationServerConfigurer.init(HttpSecurity)` for every endpoint (token, jwks, authorization, revocation, introspection, PAR, device). A single chain with `securityMatcher("/o/**")` and *relative, tenant-agnostic* endpoint settings (`tokenEndpoint("/oauth2/token")`, not `/o/{org}/oauth2/token`) now matches every organization's requests without one bean per tenant.

2. **The issuer itself resolves per-request from the same relative settings, with no custom code.** `AuthorizationServerContextFilter$IssuerResolver.resolve(HttpServletRequest)` (decompiled in full): when `AuthorizationServerSettings.getIssuer()` is `null` (which `multipleIssuersAllowed(true)` requires), it takes the request's actual path, finds whichever configured `*-endpoint` value the path *contains*, strips that suffix off, and rebuilds the issuer as `scheme://host:port` + whatever prefix remains — e.g. a request to `/o/3fa8.../oauth2/token` with `tokenEndpoint("/oauth2/token")` configured resolves the issuer to `http://host:port/o/3fa8...`, exactly ADR-0010 §5's required shape, with zero custom resolver code.

3. **OIDC discovery is now natively multi-tenant too — the spike's entire Appendix A custom filter is obsolete.** `OidcProviderConfigurationEndpointFilter` in 7.1.0 matches `/**/.well-known/openid-configuration` whenever `AuthorizationServerContextHolder.getContext().getAuthorizationServerSettings().isMultipleIssuersAllowed()` is true (decompiled `createRequestMatcher()`/`lambda$createRequestMatcher$0`), and builds the discovery document per-request from `AuthorizationServerContext.getIssuer()`/`getAuthorizationServerSettings()` rather than from filter-construction-time state. This is precisely what Appendix A's ~40-line custom filter was hand-built to do against 1.4.1; the framework itself now does it.

4. **`AuthorizationServerContextFilter` runs early enough to build on.** Decompiled insertion point: `addFilterAfter(contextFilter, SecurityContextHolderFilter.class)` — before `OAuth2ClientAuthenticationFilter` and the token endpoint filter. `AuthorizationServerContextHolder.getContext()` (thread-local) is therefore reliably populated with the correctly-resolved per-tenant issuer for the *entire remainder* of request processing on that thread, including inside application-owned code such as a custom `RegisteredClientRepository` or `JWKSource`.

**What is still genuinely custom, and now the actual scope of Task #19:**

- **`JWKSource` remains a fixed field on `NimbusJwkSetEndpointFilter`, set once at chain-construction time** (decompiled constructor: `this.jwkSource = jwkSource` — no per-request re-resolution inside the filter itself). This is unchanged from the original spike's §5.3 finding. The fix is the same shape as Appendix B, relocated one level up: build **one** `JWKSource<SecurityContext>` bean whose own `get(JWKSelector, SecurityContext)` implementation reads `AuthorizationServerContextHolder.getContext().getIssuer()`, parses `{organizationId}` back out of it, and looks up that Organization's actual key material (`SigningKeyRepository.findActive` + `OrganizationSigningKeyMaterialFactory.keyPairFor`, both already built). Wire that single dynamic instance via `http.setSharedObject(JWKSource.class, ...)` and into `NimbusJwtEncoder`, exactly Appendix B's mechanics — the dynamism now lives inside the `JWKSource` implementation instead of in per-tenant bean selection.
- **`RegisteredClientRepository` is a single shared bean, not one-repository-per-tenant** — so cross-tenant isolation is no longer structural-by-construction (§5.4's original guarantee: "a client from org-a literally cannot be found by org-b's repository instance"). With one dynamic chain there is exactly one repository instance for every organization, backed by `OAuthClientRepository.findByClientId(clientId)` (clientId is globally unique — confirmed by the new `ux_oauth_clients_client_id` index). The repository implementation must explicitly re-derive the current tenant the same way (`AuthorizationServerContextHolder` → parsed `organizationId`) and reject (return `null`, which SAS surfaces as `invalid_client`) any resolved `OAuthClient` whose own `organizationId` doesn't match. **This changes the guarantee's nature — enforced by application code we write and test, not by object-graph isolation — and needs the dedicated live cross-tenant rejection test §6 already called for, not just a happy-path test**, before this is treated as satisfying ADR-0010 §5's "structurally impossible, not policy-disallowed" bar.
- Malformed or unknown `{organizationId}` segments (JWKS/token request for an Organization that doesn't exist, or an unparseable path) need explicit, tested handling — the original spike's two-hardcoded-tenants topology never had to consider this; a dynamic single chain serving arbitrary path segments does.

**Revised recommendation:** proceed with a single dynamic `SecurityFilterChain` (`securityMatcher("/o/**")`, `multipleIssuersAllowed(true)`, relative endpoint settings) for the real per-Organization OIDC issuer, rather than the original body's implied N-static-chains approach. This removes an entire category of "how do we add a `SecurityFilterChain` bean for an Organization created after the app already started" problem that the original two-hardcoded-tenants design would otherwise have forced onto Task #19.

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
