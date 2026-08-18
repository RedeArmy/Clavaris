# ADR-0006: Standard OIDC/OAuth2 as the primary interface (not a bespoke API)

**Status:** ✅ Aprobado

## Context

Clavaris's core value proposition is "integrate from any language in under a day" (`vision-document.md` §6). That claim only holds if the primary integration surface is a standard every mainstream language already has a mature client library for.

## Decision

The primary interface is standard **OIDC/OAuth2**: discovery document, `/authorize`, `/token`, `/userinfo`, `/jwks.json`, `/revoke`, end-session endpoint — all conformant to the OpenID Foundation's Authorization Code flow with PKCE. A secondary **management API** (organizations, invitations, user administration) exists for capabilities OIDC itself doesn't cover, protected via the `client_credentials` grant — still OAuth2, never a separate proprietary auth scheme layered on top.

## Consequences

- **Positive:** every mainstream language has a mature, well-tested OIDC client library — no Clavaris-authored SDK required, no SDK maintenance burden across languages.
- **Positive:** conformance is externally verifiable (OpenID Foundation conformance test suite, `vision-document.md` §2 success metric), giving an objective bar instead of a subjective "seems compatible."
- **Negative:** the management API, while still OAuth2-protected, is still a Clavaris-specific contract (no equivalent standard exists for "manage organizations") — consumers integrating deeply with organizations will always need Clavaris-specific integration code for that part, unlike the login flow itself.
- **Negative:** constrains implementation choices to what Spring Authorization Server and standard OIDC allow — any Clavaris-specific extension to the login flow itself (e.g. custom consent screen logic) has to work within the standard's extension points, not outside them.

## Alternatives considered

- **A bespoke proprietary API with hand-written SDKs per language** — rejected: directly contradicts the "any language, under a day" goal; every new supported language would mean writing and maintaining a new SDK.
