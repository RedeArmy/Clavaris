# Market and Alternatives Research — Clavaris

🟡 En revisión

This is not commercial market research in the traditional sense — Clavaris is not (yet) a commercial product (`vision-document.md` §5). This document instead evaluates the real alternatives that were live options before choosing to build, to keep ADR-0001's reasoning honest and revisitable.

## 1. Alternatives evaluated

| Product | Model | Why it wasn't chosen |
|---|---|---|
| **Auth0** | Hosted, per-MAU pricing | Pricing scales against a portfolio of side projects rather than one funded product; vendor lock-in on the hardest component to migrate later |
| **Clerk** | Hosted, per-MAU pricing, strong DX (this project's explicit UX inspiration — see `CLAUDE.md` §1 origin) | Same pricing/lock-in concern as Auth0; also closed-source, so "reuse across any language" is bounded by whatever SDKs Clerk chooses to ship. Deep-dive feature/criticism analysis: `clerk-feature-analysis.md` |
| **Keycloak** | Self-hosted, open source, Java/Quarkus-based | Mature and OIDC-conformant, but admin-console-first and heavyweight to extend with custom domain concepts (e.g. this project's `organization-module` shape); steep operational learning curve for a solo developer |
| **Zitadel** | Self-hosted or hosted, open source, Go-based | Strong multi-tenancy model (close to what `organization-module` wants), but a different language/ecosystem than the rest of this author's stack, adding an operational surface the author isn't already fluent in |
| **Ory (Kratos/Hydra)** | Self-hosted, open source, composed of separate services | Correct separation of concerns (identity vs. OAuth2), but the "compose several services yourself" model reintroduces integration complexity that a solo developer is trying to avoid |
| **SuperTokens** | Self-hosted or hosted, open source | Closest in spirit to "developer-first, reusable," but its core session model and extensibility patterns are less aligned with a standard OIDC-everything integration story than building directly on Spring Authorization Server |

## 2. Why "build on Spring Authorization Server" beats both ends of this table

Every self-hosted alternative in the table above is a **finished product** with its own opinions, admin UI, and extension model — adopting one means adapting to its shape. Every hosted alternative solves the pricing/control problem in the wrong direction for this project's constraints. Spring Authorization Server is neither — it's a **protocol-compliance library**: it gets the OAuth2/OIDC state machine, PKCE, and JWKS right (the part that's genuinely dangerous to get wrong by hand) while leaving the product (accounts, organizations, admin surface) to be built to this project's own shape. This is the "why build instead of adopt" reasoning from `vision-document.md` §4, restated with the concrete alternatives that made it a real decision rather than a default.

## 3. Positioning

Clavaris is not currently trying to compete with any product in this table commercially. It exists to serve this author's own portfolio of projects. Should that change (a real multi-tenant commercial offering), this document and the constraints in `project-charter.md` §6 would need a full revisit — that is a different project with different economics, not a natural extension of this one.
