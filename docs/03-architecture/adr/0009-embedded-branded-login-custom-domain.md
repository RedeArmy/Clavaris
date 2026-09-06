# ADR-0009: Embedded, branded login via per-client custom domain (CNAME) + iframe-modal presentation

**Status:** 🟡 Propuesta, implementada — código real y probado (2026-09-05: `ClientBranding`, `ClientDomainConfig` con verificación DNS TXT real vía JNDI, `CustomDomainRequestRewriteFilter`, relajación de `frame-ancestors` vía CSP para `display=modal`), pendiente de revisión formal antes de considerarse ✅ Aprobado y añadirse a la lista de ADRs vigentes del proyecto — mismo patrón que ADR-0010 (implementado, formal review pendiente, no bloqueante para seguir construyendo encima)

## Context

A consumer application wants its login experience to feel entirely native — no full-page navigation to a visibly different domain, matching its own branding, embedded as a modal inside its own page — while `identity-module` still owns all authentication logic (BR-CLIENT-03's PKCE-everywhere philosophy). This is a legitimate, recurring request (it's the default expectation set by Clerk, Auth0, and every modern hosted-auth product), not a one-off.

Two constraints collide here:

1. **Security:** the login form must render inside Clavaris's own origin, never inside the consumer's own JavaScript context — otherwise a password briefly exists in code Clavaris doesn't control, reopening exactly the attack class PKCE-everywhere (BR-CLIENT-03) and the explicit rejection of the resource-owner-password-credentials grant (`prd-mvp.md` §2.2) already close. An embedded *widget* whose JS talks directly to Clavaris's API (the pattern Clerk's `<SignIn/>` component uses by default) is **rejected** for this reason — see §"Rejected alternative" below.
2. **Browser cookie policy:** if the login UI renders in an `<iframe>` pointed at Clavaris's own domain (`clavaris.io`), that domain is a **third-party site** relative to the consumer's page (`jobseeker.com`). Safari's ITP and Chrome's third-party-cookie deprecation increasingly block exactly this — Clavaris's session cookie would be silently dropped, breaking the flow intermittently and unpredictably (worse than an obvious failure).

Clerk itself hits the identical constraint and solves it identically: production Clerk instances are **required** to configure a CNAME record pointing a subdomain of the consumer's own domain (e.g. `clerk.jobseeker.com`) at Clerk's Frontend API, with an HTTP-proxy fallback for consumers who can't touch DNS ([Clerk docs](https://clerk.com/docs/guides/dashboard/dns-domains/proxy-fapi)). This validates the mechanism independently of this project's own reasoning — it's the standard answer to this exact problem, not a novel workaround.

## Decision

### 1. Presentation: iframe-modal over Clavaris's hosted login page, not an embedded widget

The consumer's frontend opens a modal overlay containing an `<iframe>` pointed at Clavaris's Authorization Code endpoint (`display=modal` query param, purely presentational — the OIDC flow underneath is unchanged, ADR-0006). The page rendered inside that iframe is Clavaris's own Thymeleaf-rendered hosted UI, themed per `OAuthClient` (§2 below). The password is entered and submitted entirely within Clavaris's origin — the consumer's JavaScript never sees it, preserving the same security property a full-page redirect has.

On successful authentication, Clavaris redirects (within the iframe) to the client's `redirect_uri`, which runs a small callback page that does **not** render normal application UI — it immediately `postMessage`s the authorization code to the parent window and the parent closes the modal and completes the code exchange server-to-server, per the standard flow.

**Social login is explicitly excluded from the iframe.** Google (and most major OAuth providers) refuses to render its consent screen inside a third-party `<iframe>` as an anti-clickjacking measure Clavaris cannot bypass or negotiate around. When a user selects a social provider inside the modal, that specific leg opens in a `window.open()` popup (full navigation to Google, standard OAuth), which closes itself and hands control back to the iframe once complete. This is an accepted, unavoidable UX seam — not a gap to "fix" later.

### 2. Cookie same-site-ness: mandatory custom domain per `OAuthClient` in production

A production `OAuthClient` (BR-CLIENT-04, new) must configure one of:

- **CNAME mode** (recommended, mirrors Clerk): the consumer points a subdomain they control (e.g. `login.jobseeker.com`) at Clavaris via CNAME. Clavaris's session cookie is scoped to that subdomain. Because the subdomain shares the consumer's own registrable domain (eTLD+1), browsers treat it as **same-site**, not third-party — ITP and Chrome's cookie deprecation don't apply.
- **Proxy mode** (fallback, mirrors Clerk's own fallback for consumers who can't touch DNS, built alongside CNAME rather than deferred — see "Open questions" below): the consumer runs a thin reverse proxy on their own infrastructure forwarding a path/subdomain to Clavaris. Same same-site cookie outcome, different operational shape — the consumer owns the proxy instead of a DNS record.
- **Shared mode** (default, development only): no custom domain — Clavaris's own domain is used directly. This is what every client gets today and remains fine for local development and testing, but is explicitly **not eligible for production traffic** once this ADR ships (BR-CLIENT-04), because the embedded-modal experience silently degrades (cookie loss) without a clear error signal — the same failure class the mandatory external security review gate exists to catch before real users see it.

Domain ownership is verified before a `CNAME`/`PROXY`-mode client is marked active (standard DNS TXT-record challenge — `client-registry-module`'s `ClientDomainConfig`/`VerifyClientDomainOwnershipService`, a real lookup via the JDK's own built-in JNDI DNS provider, admin-triggered, not a background poller), same category of control as any certificate-authority domain validation — prevents a client claiming a domain it doesn't control.

**TLS termination is explicitly out of scope as an in-JVM feature** (confirmed scoping decision, not an oversight): Clavaris never runs an ACME client or issues certificates itself. A verified custom domain's actual TLS termination is an operator/infrastructure concern — a reverse proxy (Caddy, Traefik, nginx+certbot) in front of Clavaris that already automates per-domain certificates, or a manually uploaded cert — documented as a deployment runbook item, not built here. This is a deliberate scope line, not the certificate-issuance gap the "Open questions" section below used to describe.

Host→Organization resolution for a request arriving on a verified custom domain (no `/o/{organizationId}` path prefix at all) is `app`'s own `CustomDomainRequestRewriteFilter` — a servlet filter registered ahead of Spring Security's own filter chain, internally forwarding the request to the resolved `/o/{organizationId}/...` path once the `Host` header matches a `VERIFIED` `ClientDomainConfig`. Static assets and `/actuator/**` are excluded from rewriting (they are genuinely origin-wide, not tenant-scoped).

### 3. Branding as data on `OAuthClient`

A new `ClientBranding` entity (one-to-one, optional, with `OAuthClient` — same "separate table, not a bolted-on nullable column" convention as `PasswordCredential`/`SocialIdentity`, `data-model.md` §2) carries logo URL, primary color, and application display name, read by the Thymeleaf hosted-UI templates to theme the login/consent screens per client.

### 4. Iframe embedding eligibility and CSP `frame-ancestors` relaxation

`display=modal` on the login page (forwarded through `OrganizationLoginRedirectEntryPoint` alongside `client_id` when an unauthenticated `/oauth2/authorize` request first lands) signals that the page is being rendered inside a consumer's own iframe. `app`'s `ContentSecurityPolicyHeaderWriter` relaxes `frame-ancestors` from `'none'` to that one client's own registered `embeddingOrigin` — a new field on `ClientDomainConfig`, deliberately separate from `redirectUris` (a consumer's top-level embedding page is not necessarily one of its OAuth2 callback URLs) — only when `EmbeddingEligibilityChecker` resolves the client as eligible: a `VERIFIED` `ClientDomainConfig` with a registered `embeddingOrigin` in production, or unconditionally (a wildcard origin, with a warning logged) for a `DEVELOPMENT` Organization's client — a deliberate, documented testing convenience, not production-hardened. `X-Frame-Options: DENY` is deliberately left untouched everywhere (every evergreen browser gives CSP `frame-ancestors` precedence over it per the CSP Level 2 spec, so the relaxation above already works in practice) — disabling it chain-wide would have also stripped it from this same chain's non-HTML JSON responses (`/oauth2/token`, `/userinfo`), a real regression for a benefit that only matters to browsers too old to understand CSP framing directives at all.

Social-provider links inside the iframe open via `window.open()` (client-side, `embedded-login-popup.js`) rather than navigating the iframe itself — every major provider's own consent screen refuses to render inside an iframe regardless, so an in-frame click would otherwise dead-end. The popup's own final `redirect_uri` page is **not built or hosted by Clavaris** — it lives on the consumer's own origin, and must itself relay the outcome back to the opening window via `window.opener.postMessage(...)` before closing itself; a reference implementation is documented in `embedded-login-popup.js`'s own header comment.

## Consequences

- **Positive:** closes a real, validated gap (`clerk-feature-analysis.md` §6/§7) without weakening the security model — the password never leaves Clavaris's origin, unlike the rejected widget-embed alternative.
- **Positive:** the CNAME requirement solves branding *and* cookie same-site-ness with a single mechanism — not two separate features.
- **Positive:** independently validated by Clerk's own production requirement being identical in shape (CNAME + proxy fallback) — reduces the risk this is a bespoke, untested design.
- **Negative:** real new operational surface — DNS ownership verification and proxy-mode support are now built and tested, but per-domain TLS certificate issuance is deliberately **not** Clavaris's own job (see §2 above) — every operator stands up their own reverse proxy/certificate automation before pointing a real custom domain at Clavaris; `technical-debt-register.md` tracks this as a deployment-runbook gap, not an open code gap.
- **Negative:** every consumer wanting the embedded/branded experience in production must complete a DNS or proxy setup step before launch — a real integration-cost increase against the "under a day" goal (`nfr-quality-attributes.md` §4) for *this specific* login experience. The plain redirect flow (Option A from the earlier design conversation — full-page navigation to Clavaris's own hosted page, no custom domain needed) remains available with zero extra setup for consumers who don't need the embedded feel; this ADR adds a capability, it does not remove the simpler existing path.
- **Negative:** true silent SSO *across* unrelated consumer domains remains out of reach (browsers correctly block this regardless of what Clavaris does) — not attempted here; flagged as a deliberate non-goal, not an oversight (see Alternatives).

## Alternatives considered

- **Embedded widget calling Clavaris's API directly from the consumer's own JS** (Clerk's default `<SignIn/>` pattern) — **rejected**: reopens the attack surface PKCE-everywhere and the ROPC exclusion exist to close (a password would transiently exist in code Clavaris doesn't control). Rejected on the same grounds as `clerk-feature-analysis.md` §3/§6 already established.
- **Storage Access API as the primary mechanism instead of a custom domain** — kept as a documented fallback for `SHARED` mode only, not the primary answer: it requires a user gesture and inconsistent per-browser support, versus the custom-domain approach which works unconditionally once configured.
- **FedCM (Federated Credential Management API)** for true cross-site silent SSO — not pursued now: browser-controlled UI conflicts directly with the branding goal this ADR exists to satisfy, and support is Chromium-first, not universal yet. Worth revisiting if a genuine multi-consumer-SSO need becomes real (same trigger condition as the multi-consumer-identity open question, `business-rules.md` BR-DATA).

## Open questions

Both questions this ADR originally left open are now resolved:

- **Certificate issuance failure handling** — resolved by scoping TLS issuance out of Clavaris entirely (§2 above): there is no Clavaris-side certificate-issuance failure state to design for, since Clavaris never issues one. DNS *ownership-verification* failure (a real, distinct concern — the TXT record doesn't match) is a `FAILED` `DomainVerificationStatus`, a normal retryable outcome an operator can act on by re-checking the published record and re-triggering verification, not a silent pending-forever state.
- **Whether `PROXY` mode is worth building for v1** — resolved: built alongside `CNAME` in this pass (explicit override of this ADR's own original "leaning toward deferring" lean), same `ClientDomainConfig`/DNS-ownership-verification model serving both modes identically — the mode field only ever affects operator-facing documentation of what to configure, not any code path.

Genuinely still open, not part of this pass's scope:

- A real operator-facing runbook for standing up a reverse proxy/certificate automation in front of a verified custom domain (`technical-debt-register.md`).
- `PROXY` mode's own operational documentation — the DNS-ownership-verification model is shared with `CNAME`, but the proxy itself is entirely consumer-run infrastructure this ADR doesn't prescribe.
