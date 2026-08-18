# ADR-0001: Build a custom identity engine vs. adopt an existing IdP

**Status:** ✅ Aprobado

## Context

Clavaris needs to exist before any consumer application (starting with JobSeeker) can integrate a real login flow. Three broad options exist: adopt a hosted IdP (Auth0, Clerk), adopt a self-hosted turnkey IdP (Keycloak, Zitadel, Ory, SuperTokens), or build a custom identity provider on top of a protocol-compliance library. Full comparison: `docs/00-vision/market-research.md`.

## Decision

Build a custom identity provider, using **Spring Authorization Server** as the OIDC/OAuth2 protocol-compliance foundation (ADR-0003) rather than hand-rolling the protocol layer, and rather than adopting a finished product.

## Consequences

- **Positive:** full control over the data model, the ability to shape `organization-module` exactly as this project's consumers need it, no per-MAU pricing risk, no vendor lock-in on the hardest component to migrate later.
- **Positive:** protocol correctness (the genuinely dangerous part to get wrong) is delegated to a vetted library, not reinvented.
- **Negative:** significantly more development time than adopting Auth0/Clerk/Keycloak — accepted explicitly, per the user's own instruction that development time is not a constraint for this project.
- **Negative:** ongoing operational burden (uptime, key rotation, security patching) falls entirely on a solo developer — see `project-charter.md` §5 and §6 (Spring Authorization Server maintenance-risk assumption).
- **Negative:** "reusable across projects" is a claim this decision makes possible, not one it proves — it's only validated once a second real consumer integrates (`vision-document.md` §6).

## Alternatives considered

See `docs/00-vision/market-research.md` for the full comparison table (Auth0, Clerk, Keycloak, Zitadel, Ory, SuperTokens) and why each was rejected.
