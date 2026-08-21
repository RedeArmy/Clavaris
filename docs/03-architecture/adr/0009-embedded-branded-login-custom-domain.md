# ADR-0009: Embedded, branded login via per-client custom domain (CNAME) + iframe-modal presentation

**Status:** 🟡 Propuesta — pendiente de revisión antes de considerarse ✅ Aprobado y añadirse a la lista de ADRs vigentes del proyecto

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

- **CNAME mode** (recommended, mirrors Clerk): the consumer points a subdomain they control (e.g. `login.jobseeker.com`) at Clavaris via CNAME. Clavaris's session cookie is scoped to that subdomain. Because the subdomain shares the consumer's own registrable domain (eTLD+1), browsers treat it as **same-site**, not third-party — ITP and Chrome's cookie deprecation don't apply. TLS for the subdomain is provisioned by Clavaris (SNI-based dynamic certificate issuance, e.g. via Let's Encrypt) once DNS ownership is verified.
- **Proxy mode** (fallback, mirrors Clerk's own fallback for consumers who can't touch DNS): the consumer runs a thin reverse proxy on their own infrastructure forwarding a path/subdomain to Clavaris. Same same-site cookie outcome, different operational shape — the consumer owns the proxy instead of a DNS record.
- **Shared mode** (default, development only): no custom domain — Clavaris's own domain is used directly. This is what every client gets today and remains fine for local development and testing, but is explicitly **not eligible for production traffic** once this ADR ships (BR-CLIENT-04), because the embedded-modal experience silently degrades (cookie loss) without a clear error signal — the same failure class the mandatory external security review gate exists to catch before real users see it.

Domain ownership is verified before a `CNAME`/`PROXY`-mode client is marked active (standard DNS TXT-record challenge), same category of control as any certificate-authority domain validation — prevents a client claiming a domain it doesn't control.

### 3. Branding as data on `OAuthClient`

A new `ClientBranding` entity (one-to-one, optional, with `OAuthClient` — same "separate table, not a bolted-on nullable column" convention as `PasswordCredential`/`SocialIdentity`, `data-model.md` §2) carries logo URL, primary color, and application display name, read by the Thymeleaf hosted-UI templates to theme the login/consent screens per client.

## Consequences

- **Positive:** closes a real, validated gap (`clerk-feature-analysis.md` §6/§7) without weakening the security model — the password never leaves Clavaris's origin, unlike the rejected widget-embed alternative.
- **Positive:** the CNAME requirement solves branding *and* cookie same-site-ness with a single mechanism — not two separate features.
- **Positive:** independently validated by Clerk's own production requirement being identical in shape (CNAME + proxy fallback) — reduces the risk this is a bespoke, untested design.
- **Negative:** real new operational surface — dynamic TLS certificate issuance per client domain, DNS ownership verification, proxy-mode support — none of this exists in the current module skeletons and needs its own implementation-time design pass (SNI routing at minimum).
- **Negative:** every consumer wanting the embedded/branded experience in production must complete a DNS or proxy setup step before launch — a real integration-cost increase against the "under a day" goal (`nfr-quality-attributes.md` §4) for *this specific* login experience. The plain redirect flow (Option A from the earlier design conversation — full-page navigation to Clavaris's own hosted page, no custom domain needed) remains available with zero extra setup for consumers who don't need the embedded feel; this ADR adds a capability, it does not remove the simpler existing path.
- **Negative:** true silent SSO *across* unrelated consumer domains remains out of reach (browsers correctly block this regardless of what Clavaris does) — not attempted here; flagged as a deliberate non-goal, not an oversight (see Alternatives).

## Alternatives considered

- **Embedded widget calling Clavaris's API directly from the consumer's own JS** (Clerk's default `<SignIn/>` pattern) — **rejected**: reopens the attack surface PKCE-everywhere and the ROPC exclusion exist to close (a password would transiently exist in code Clavaris doesn't control). Rejected on the same grounds as `clerk-feature-analysis.md` §3/§6 already established.
- **Storage Access API as the primary mechanism instead of a custom domain** — kept as a documented fallback for `SHARED` mode only, not the primary answer: it requires a user gesture and inconsistent per-browser support, versus the custom-domain approach which works unconditionally once configured.
- **FedCM (Federated Credential Management API)** for true cross-site silent SSO — not pursued now: browser-controlled UI conflicts directly with the branding goal this ADR exists to satisfy, and support is Chromium-first, not universal yet. Worth revisiting if a genuine multi-consumer-SSO need becomes real (same trigger condition as the multi-consumer-identity open question, `business-rules.md` BR-DATA).

## Open questions

- Certificate issuance failure handling (DNS misconfigured, propagation delay up to 48h per Clerk's own documented experience) — needs a clear client-facing status/error state, not a silent pending-forever domain.
- Whether `PROXY` mode is worth building for v1 of this feature or deferrable until a real consumer asks for it specifically (CNAME covers the common case) — leaning toward deferring, not yet decided.
