# ADR-0021: SAML 2.0 enterprise SSO + SCIM 2.0 provisioning — scope, library choice, phased plan

**Status:** 🟡 En revisión (2026-09-03) — a plan and a set of decisions, not an implementation. No
code exists for anything this ADR describes; see `technical-debt-register.md` TD-FUT-023 for the
tracking row this ADR now closes the open question on (a *plan* exists, the *work* is still
Pending).

## Context

Requested directly: "SAML/SCIM, muy similar al implementado en Clerk, 100% GA (no beta)." That
request was evaluated honestly rather than built speculatively in one pass — the user explicitly
chose, when asked, to get Impersonation built to full production quality immediately and this ADR
instead of a rushed implementation (`AskUserQuestion`, this session). CLAUDE.md §6 is unambiguous
that this project's own external security-review gate is non-negotiable before any consumer sends
real user traffic through *anything* this system issues tokens for — SAML in particular carries a
well-documented industry history of subtle signature-validation vulnerabilities (XML Signature
Wrapping, XXE, "Golden SAML" forged-assertion attacks), so treating this as buildable-and-shippable
in a single sitting would have been a real misrepresentation of both the effort and the risk
involved, not a compressed timeline.

TD-FUT-023 (ADR-0020 Decision 5) already named this as a separate, larger initiative from social
login, deferred pending a real consumer request and "likely its own ADR." This is that ADR.

## Decision 0 — correcting the scope framing from the initial request

"SAML/SCIM like Clerk" is easy to mis-scope as "Clavaris becomes a SAML Identity Provider." It does
not. Clerk's enterprise-SSO feature — and every comparable product (Auth0, Okta, WorkOS) — puts the
IdP *product* in the role of **Service Provider (SP)** for SAML, and **SCIM server** for
provisioning:

- **SAML**: the enterprise customer (e.g. a JobSeeker customer, "Acme Corp") runs its **own**
  Identity Provider — Okta, Entra ID (Azure AD), OneLogin, Google Workspace. Clavaris's job is to
  redirect an end user to *that* IdP, receive and validate a signed SAML Response/Assertion back at
  Clavaris's own Assertion Consumer Service (ACS) endpoint, and resolve it to an `Account` —
  structurally the SAML-shaped sibling of the OAuth-shaped social login ADR-0020 already built, not
  a new category of feature.
- **SCIM**: the same enterprise customer's IdP is the SCIM **client**; Clavaris is the SCIM
  **server**. Acme Corp's Okta pushes user create/update/deactivate calls into Clavaris to keep
  Workspace membership in sync with their own directory — the inbound-provisioning sibling of
  `AddWorkspaceMemberService`/`RemoveWorkspaceMemberService` (already shipped), called from a new
  protocol adapter instead of the admin API.

Getting this backwards (building Clavaris as a SAML *IdP*) would produce a feature nobody asked for
and that doesn't compose with the product's own multi-tenant model at all — recorded here explicitly
so it's never re-derived incorrectly mid-implementation.

## Decision 1 — scope of federation: `Workspace`-level, not `Organization`-level

**Enterprise SAML/SCIM connections are configured per `Workspace`, never per `Organization`.** This
is the one decision in this ADR most likely to be gotten wrong by analogy to ADR-0020, so it's
called out first and explicitly.

`Organization` is one whole consuming system's account pool (e.g. "JobSeeker" is one `Organization`,
CLAUDE.md §5) — social login's Decision 4 (ADR-0020) correctly scoped *that* provider config
Organization-wide because Google/GitHub are Clavaris-owned shared apps serving every one of that
consumer's end users identically. Enterprise SSO is the opposite shape: the party bringing their own
IdP is one specific *customer of the consuming application* — in Clerk's own terminology this is
literally called an "Enterprise Connection" scoped to one of *their* Organizations, which this
codebase's own ADR-0010 terminology remap makes exactly Clavaris's `Workspace` (a team/company
grouping within one `Organization`'s account pool). "Acme Corp" (a `Workspace` inside the
`Organization` "JobSeeker") brings its own Okta; "Beta Inc" (a different `Workspace`, same
`Organization`) brings its own Entra ID, or none at all. An `Organization`-level connection would
force every one of a consumer's own enterprise customers to share one IdP — structurally wrong.

A new `workspace_saml_connections` table (one row per `Workspace`, nullable/absent = SSO not
configured for that Workspace) and a new `workspace_scim_tokens` table (bearer credentials scoped to
one `Workspace`) are the natural homes for this — both extend `organization-module`'s existing
`Workspace` aggregate, not a new bounded context.

## Decision 2 — library choice: Spring Security's own SAML2 Service Provider support, not raw OpenSAML

ADR-0003's own bar — "build on a vetted framework, never hand-roll the protocol state machine" —
applies here exactly as it did to OAuth2/OIDC. **`spring-security-saml2-service-provider`**
(first-party Spring Security, not a third-party library) is the correct match: it provides
`.saml2Login(...)`, `RelyingPartyRegistration`/`RelyingPartyRegistrationRepository`,
`Saml2AuthenticationProvider` and the ACS endpoint filter, all built on OpenSAML internally but
exposed through the same Spring Security configurer DSL `OrganizationAuthorizationServerConfig`
already uses for everything else. Raw OpenSAML directly (the initial in-session framing, before this
ADR corrected it) would mean owning XML signature validation, canonicalization, and assertion
parsing by hand — precisely the class of code this project has never written and has no reason to
start writing now that a vetted, same-ecosystem alternative exists.

**The real, non-trivial work is multi-tenancy, not the protocol.** `RelyingPartyRegistrationRepository`
is not natively multi-tenant the way Spring Authorization Server's `multipleIssuersAllowed` is
(confirmed by reading the interface — no dynamic-resolution equivalent ships out of the box) — this
needs the same shape of dynamic, database-backed resolver
`OrganizationRegisteredClientRepository`/`OrganizationScopedJwkSource` already are for OAuth2,
resolving a `RelyingPartyRegistration` from the current `Workspace` (via its SSO-initiation path,
e.g. `/o/{organizationId}/login/saml2/{workspaceId}`) instead of a fixed, single, statically-declared
registration. This is genuinely spike-shaped work, comparable in size to spike 0001
(spring-authorization-server-multitenancy) — plan for a dedicated design spike before real
implementation starts, not an assumption that the DSL "just" supports it.

For SCIM, no comparable first-party Spring library exists — SCIM 2.0 is fundamentally a CRUD REST
API with a specific JSON resource schema, filter query grammar (RFC 7644 §3.4.2.2), and bearer-token
auth, not a protocol state machine the way OAuth2/OIDC or SAML are. **UnboundID SCIM2 SDK**
(`com.unboundid.product.scim2:scim2-sdk-server`) is the recommended library for resource
modeling/JSON (de)serialization and filter-expression parsing — the REST endpoints, persistence
mapping onto `Workspace`/`WorkspaceMembership`, and auth remain this codebase's own code either way,
which does not conflict with ADR-0003's spirit the way hand-rolling OAuth2 would have.

## Decision 3 — signing/verification key material: one Clavaris-wide SP entity, not per-Workspace

Clavaris's own SAML SP identity (Entity ID, ACS URL, and the certificate it signs
`AuthnRequest`s/decrypts assertions with) is **one shared, Clavaris-wide value**, not one per
`Workspace` or per `Organization` — the same "one shared identity, many varying counterparties"
shape ADR-0020 Decision 4 already chose for the Google/GitHub OAuth apps, and how every comparable
multi-tenant IdP product (Auth0, Okta, WorkOS) presents itself to the *enterprise's* IdP: one SP
entity id per environment, many `RelyingPartyRegistration` rows (one per `Workspace`) varying only in
which counterparty IdP they point at. Per-`Workspace` SP certs would be real, unrequested complexity
with no security benefit — the SP's own key material only has to be trustworthy to whichever IdP a
given enterprise customer configures it against, not isolated *from* other Workspaces the way
ADR-0010's per-Organization JWKS isolation is (that isolation protects against one tenant forging
tokens as another **inside Clavaris's own token-issuance model**; nothing analogous is at stake in
one shared SP identity being *presented to* many external IdPs).

Rotation is out of scope for v1 of this feature (manually re-uploaded the same way an
Organization's `PLATFORM_BOOTSTRAP_CLIENT_SECRET` is today) — a real gap worth its own follow-up row
once this ships, not a blocker to designing the rest of the feature now.

## Decision 4 — account linking trust model: domain-gated auto-provisioning, not ADR-0020's confirmation step

ADR-0020 Decision 1 required explicit email-confirmation before linking a social login to an
existing `Account`, because anyone can pre-register any unverified email through
`RegisterAccountController` and social login alone can't tell a real owner from a squatter. SAML SSO
carries a materially stronger trust signal that changes this calculus: a `Workspace` admin
*explicitly configures* one specific IdP for one specific verified email domain (e.g. `@acme.com`)
before any SSO login can happen at all — the assertion isn't "some provider says this email is
verified," it's "the enterprise's own directory, which this Workspace's own admin vouched for by
configuring it, says this specific person exists in it." **Recommendation for the eventual
follow-up ADR/implementation pass (not finally decided here): auto-provision or auto-link an
`Account` from a SAML assertion when, and only when, the asserted email's domain matches the
`Workspace`'s own configured allowed domain(s)** — closing the same squatting scenario ADR-0020
worried about by gating on domain ownership (established out-of-band, by the admin doing the SSO
configuration) rather than by a confirmation email. This is flagged as a recommendation, not a final
decision, because it's a real security-relevant call that deserves its own dedicated review at
implementation time, not one paragraph in a planning ADR — Phase 0 below names it explicitly as a
required pre-implementation decision.

## Decision 5 — SCIM surface: Users only in v1, no Groups

`PUT/POST/PATCH/DELETE /scim/v2/workspaces/{workspaceId}/Users` — create, update (attribute changes,
`active: false` for deactivation), and delete map directly onto the already-shipped
`AddWorkspaceMemberService`/`ChangeWorkspaceMemberRoleService`/`RemoveWorkspaceMemberService`, called
from a new SCIM protocol adapter rather than duplicated. **SCIM Groups are out of scope for v1** —
this codebase's own `WorkspaceRole` is a fixed two-value enum (`ADMIN`/`MEMBER`, ADR-0010 §3
addendum, no arbitrary role/group modeling), so there is no real "group" concept on this side for an
IdP-pushed SCIM Group to map onto yet; forcing one in now would be speculative modeling ahead of a
named use case, the exact anti-pattern CLAUDE.md §12 already warns against.

## Phased plan

Each phase is independently shippable and independently reviewable — no phase assumes a later one
already exists.

1. **Phase 0 — design spike + threat-model addendum, no product code.** Resolve
   `RelyingPartyRegistrationRepository` multi-tenancy the way spike 0001 resolved SAS's (a dedicated
   spike document under `docs/03-architecture/spikes/`); add a SAML-specific section to
   `threat-model-stride.md` naming XML Signature Wrapping, replay, and unsigned-assertion-acceptance
   explicitly, each with its own planned mitigation (Spring Security SAML2's own validated
   `Saml2AuthenticationProvider`, never custom XML handling); finally decide Decision 4's
   domain-gating question as its own reviewed sub-decision.
2. **Phase 1 — data model.** `workspace_saml_connections` (idp entity id, SSO URL, X.509 cert,
   enabled flag, allowed email domain(s)), `workspace_scim_tokens` (hashed bearer token, same
   hash-not-plaintext discipline `data-model.md` §2 already applies everywhere else). Migrations +
   `domain-model.md`/`data-model.md` updates.
3. **Phase 2 — SP-initiated SAML login.** Dynamic `RelyingPartyRegistrationRepository` resolving by
   `Workspace` (mirrors `OrganizationRegisteredClientRepository`'s own pattern); ACS endpoint;
   `/o/{organizationId}/login` gains an SSO entry point once a `Workspace` connection exists for the
   entered email's domain.
4. **Phase 3 — account provisioning from an assertion.** Implements whatever Phase 0 decided for
   Decision 4; new `application/usecase/authenticatewithsamlassertion/` (identity-module), same
   vertical-slice convention as `authenticatewithsocialprovider`.
5. **Phase 4 — SCIM server.** `/scim/v2/workspaces/{workspaceId}/Users`, bearer-token auth against
   `workspace_scim_tokens`, built on UnboundID SCIM2 SDK for schema/filter parsing, delegating to the
   existing Workspace-membership use cases (Decision 5).
6. **Phase 5 — mandatory external security review** (CLAUDE.md §6, with the extra emphasis this
   ADR's own Context section names) — specifically exercising the SAML signature-validation path,
   before any `Workspace` in any real consuming application's `Organization` can enable it.
7. **Phase 6 — GA**, only after Phase 5 closes clean. Never marketed or documented as available
   before this phase — the mistake this ADR exists to head off in the first place.

**Honest effort estimate, not a schedule commitment**: Phase 2's multi-tenant `RelyingPartyRegistration`
resolution is genuinely multi-week work, comparable to the original Spring-Authorization-Server
multi-tenancy spike; Phase 4 (SCIM) is lighter but still real weeks once RFC 7644's filter grammar
and pagination are implemented properly rather than partially. This whole initiative is realistically
a multi-month effort end to end, including Phase 5 — not a feature buildable to genuine GA quality in
a single working session, which is the reason this session produced this plan instead of a rushed
implementation.

## Consequences

- **Positive:** Decision 1 (Workspace-scoping) prevents a structurally wrong design — one shared IdP
  per `Organization` — from ever being built, catching it before any schema exists to migrate away
  from later.
- **Positive:** Decision 2 keeps this initiative inside ADR-0003's own "vetted framework" discipline
  for the protocol that most needs it (SAML's own vulnerability history) while being honest that the
  multi-tenancy work is real, not framework-provided for free.
- **Positive:** reusing `AddWorkspaceMemberService`/`RemoveWorkspaceMemberService`/
  `ChangeWorkspaceMemberRoleService` for SCIM (Decision 5) means the actual membership-mutation logic
  is already built, tested, and audited — SCIM is a new protocol adapter onto existing behavior, not
  new business logic.
- **Negative:** this is a genuinely large initiative — Phase 2 alone is spike-shaped, multi-week work
  — deliberately not compressed into a false "GA now" claim.
- **Negative:** Decision 4's domain-gated auto-provisioning, if ultimately adopted, is a real trust
  boundary this codebase doesn't have an equivalent of yet (every other identity path requires either
  a password the account holder set or an explicit confirmation step) — named here precisely so
  Phase 0 treats it as a first-class security decision, not an implementation detail.

## Alternatives considered

- **Raw OpenSAML, no Spring Security SAML2 layer** — rejected (Decision 2): would mean hand-rolling
  signature validation orchestration this project has no reason to own when a same-ecosystem, vetted
  alternative exists.
- **`Organization`-level SAML/SCIM configuration** — rejected (Decision 1): structurally wrong for
  the actual shape of enterprise SSO (one IdP per enterprise customer, not per consuming application).
- **Per-Workspace SP signing certs** — rejected (Decision 3): real complexity with no matching
  security benefit, unlike ADR-0010's per-Organization JWKS isolation, which protects a genuinely
  different threat.
- **SCIM Groups in v1** — rejected (Decision 5): no matching concept on this side yet
  (`WorkspaceRole` is a fixed enum, not arbitrary groups); speculative modeling ahead of a real need.
- **Building this to "100% GA" in the same session as Impersonation** — rejected outright (see
  Context): would have meant either a materially incomplete implementation presented as finished, or
  skipping the external security review this project's own CLAUDE.md §6 treats as non-negotiable.
