# ADR-0027: Consumer-defined Workspace roles and permissions (replaces `WorkspaceRole.ADMIN/MEMBER`)

**Status:** ✅ Approved (2026-09-26)

## Context

ADR-0010 §3's addendum (2026-08-27) deliberately narrowed `WorkspaceMembership` to a fixed, Clavaris-internal `WorkspaceRole` enum — `ADMIN | MEMBER` only, no `OWNER`, no invitations — and explicitly ruled out anything richer: "Business/product-domain roles are explicitly out of scope. Whatever a consuming application needs beyond admin/member (e.g. JobSeeker's 'recruiter' vs. 'candidate') is that application's own concern, never modeled in Clavaris." `WorkspaceRole.java`'s own Javadoc repeats this verbatim. CLAUDE.md §1/§12 state the same boundary at the project level: Clavaris "doesn't know what 'a candidate' is... if a requirement only makes sense for one specific consumer, it belongs in that consumer's own backend, not here."

A new product requirement asks for exactly the thing that addendum ruled out: each consuming system (each `Organization`) needs to define its **own** hierarchy of team roles — e.g. one consumer might need "recruiter"/"interviewer"/"hiring manager", another "supervisor"/"coordinator"/"agent" — used to decide what a given user can do and see *inside that consumer's own application*. This is a real, legitimate need; the question this ADR resolves is how to build it without reintroducing the exact business-domain knowledge ADR-0010 §3 correctly kept out.

**This ADR formally re-opens and supersedes ADR-0010 §3's addendum** (its `ADMIN`/`MEMBER`-only decision), rather than layering something beside it — confirmed explicitly during design review for this ADR, not assumed. ADR-0010 §1–§2 (tenant isolation), §4–§6 (module dependency direction, per-Organization JWKS, rate limiting) and the `Organization`/`Workspace` naming split are all unaffected.

### Prior art (Clerk, WorkOS, Auth0, FusionAuth)

Every mainstream multi-tenant IdP that supports this lands on the same shape, and none of them model an org-chart hierarchy as a first-class primitive:

- **Clerk Organizations** — built-in `org:admin`/`org:member`, plus customer-defined custom roles, each a named bundle of **opaque, customer-defined permission strings** (namespaced, e.g. `org:posts:create`). Clerk never assigns meaning to a permission string; it stores it, includes it in the session JWT (`org_role`, `org_permissions` claims), and the customer's own app calls `has({ permission })`. No native "role reports to role" relationship — reporting/org-chart hierarchy is left entirely to the customer's own data.
- **WorkOS / Auth0 (Organizations + RBAC) / FusionAuth** — the same pattern: per-tenant custom roles as permission bundles, assignable per member, surfaced via claims and/or a dedicated API, sometimes synced from an external IdP's groups (SCIM). None model hierarchy natively either.

The consistent lesson: **the role/permission *string* is always opaque to the IdP; only the bundling and per-tenant customization is generic infrastructure.** That is exactly the discipline this ADR needs to hold onto to stay consistent with CLAUDE.md §1/§12 — Clavaris can own "a named, per-Organization-configurable bundle of strings, with an optional hierarchy edge," while never owning what any given string means.

## Decision

### 1. Replace the `WorkspaceRole` enum with a real, `Organization`-scoped domain entity

`WorkspaceRole` stops being `ADMIN | MEMBER` and becomes a persisted entity:

```
WorkspaceRole
  id              UUID
  organizationId  UUID   -- roles are defined once per Organization (per consuming system),
                          -- shared across every Workspace that Organization owns — matches
                          -- "cada sistema consumidor podrá definir su propia jerarquía"
  name            String -- consumer-defined display label ("Interviewer", "Supervisor", ...);
                          -- 100% opaque to Clavaris, same posture as an OAuth scope string
  parentRoleId    UUID?  -- optional self-reference; see §3 (hierarchy)
  permissions     Set<String>  -- opaque, consumer-defined (e.g. "org:interviews:schedule");
                                -- stored the same way OAuthClient.scopes already are
                                -- (client-registry-module, arbitrary consumer-defined strings)
  reserved        boolean -- true only for the one system-seeded bootstrap role per Organization
                           -- (see §2) — cannot be deleted, cannot be stripped of its reserved
                           -- permissions
  createdAt       Instant
```

`WorkspaceMembership.role` (the enum field) is replaced by `WorkspaceMembership.roleId`, a **nullable** FK into `WorkspaceRole` — `null` means "member of the Workspace, no role currently assigned," an explicitly allowed state (see §5). This is a full replacement, not an additive column — confirmed explicitly (design review): no dual-write or deprecation period, because no consumer has integrated against the enum shape yet (CLAUDE.md §11: "what hasn't happened yet is JobSeeker actually integrating against a deployed instance").

### 2. A small, reserved permission namespace is the only thing Clavaris itself understands

Replacing `ADMIN`/`MEMBER` outright creates a bootstrap problem: *something* has to gate Clavaris's own management endpoints (who can add/remove a Workspace member, who can create/edit a `WorkspaceRole` itself) — a fully opaque permission model can't self-host its own authorization if Clavaris never looks at any permission string.

Resolved the same way ADR-0010 §"Organization provisioning" resolved the platform-tier bootstrap problem: a small, **reserved** namespace, structurally distinct from consumer-defined permissions, that only Clavaris's own server-side checks ever read:

- `clavaris:workspace:manage_members`
- `clavaris:workspace:manage_roles`

These two strings are the only permissions Clavaris's authorization logic ever branches on. Every other permission string in a `WorkspaceRole.permissions` set is opaque pass-through — stored, tokenized, exposed, never interpreted.

`CreateWorkspaceService` seeds one `reserved = true` `WorkspaceRole` per newly created `Workspace`'s `Organization` (first `Workspace` in that `Organization` only — the role is `Organization`-scoped, per §1) carrying both reserved permissions, and assigns it to the creating member. This replaces BR-WS-01's old invariant ("a workspace must always retain at least one `ADMIN`") with its generalized form: **a Workspace must always retain at least one membership whose role carries `clavaris:workspace:manage_members`.** This invariant guards every action that changes a membership's `roleId` — reassigning to a different role, *and* clearing it to `null` (§5) — not only removal of the membership itself: whichever action would leave zero `manage_members`-holders in a Workspace is rejected outright, same mechanism, generalized predicate instead of an enum comparison.

### 3. Optional role hierarchy via `parentRoleId`, with permission inheritance — the "further than Clerk" piece

A `WorkspaceRole` may declare a `parentRoleId`. Clavaris computes **effective permissions** for a role as its own `permissions` set unioned with its parent's effective permissions, recursively up the chain — e.g. a consumer's "Supervisor" role with `parentRoleId` pointing at "Agent" automatically inherits everything "Agent" can do, without the consumer having to duplicate the permission list at every level of their own org chart.

This is still fully opaque: Clavaris never interprets what "Supervisor" or "Agent" *mean* — it only walks a graph and unions string sets. A domain invariant enforced before persisting any `WorkspaceRole`: the `parentRoleId` chain must never form a cycle (checked at creation/update time, same category of structural invariant as the existing "at least one manage_members role" check).

### 4. Exposure — all three mechanisms, per explicit decision

- **OIDC token claims** (primary, matches Clerk's `org_role`/`org_permissions` convention): every ID/access token issued for an `Account` carries `workspace_id`, `workspace_role` (the assigned role's consumer-defined `name`), and `workspace_permissions` (the flattened, inheritance-resolved, deduplicated effective permission set) — for the **active Workspace** (§4.1).
- **Management API** — new endpoints under the existing `client_credentials`-protected surface (ADR-0006), mirroring the shape `AddWorkspaceMember`/`ChangeWorkspaceMemberRole` already use: `POST/GET/PATCH/DELETE /api/v1/organizations/{organizationId}/workspace-roles[/{roleId}]`. `ChangeWorkspaceMemberRole` changes shape from "set enum value" to "set `roleId`," same use case, generalized input.
- **Webhooks** — three new event types on the existing catalog (webhook-module, ADR-0007, `KnownWebhookEventTypeOptions`), following the existing `category.action` convention (`workspace.created`, `organization.deleted`, etc. already establish it):
  - `workspace_role.created` — "A new WorkspaceRole was created."
  - `workspace_role.updated` — "A WorkspaceRole's name, permissions, or parent role changed."
  - `workspace_role.deleted` — "A WorkspaceRole was permanently deleted."

  The existing `workspace_membership.role_changed` event (already in the catalog, pre-dates this ADR) needs no change — it already means "a member's assigned role changed," which stays true whether the role is an enum value or a `WorkspaceRole` reference.

#### 4.1 Active Workspace selection — corrected during implementation (2026-09-26): not needed in v1

The initial draft of this section assumed an `Account` could already belong to more than one `Workspace`, based on reading only `workspace_memberships`' unique index (`(workspace_id, account_id)`, not `(account_id)`). That was an incomplete read — the actual, already-shipped invariant lives one layer up: `AddWorkspaceMemberService`'s own Javadoc states plainly "v1 has no 'attach an existing Account to a second Workspace' flow, only 'create a new member'" — every add-member call provisions a brand-new `Account`, so **an `Account` cannot belong to more than one `Workspace` today, by construction, not just by convention.**

There is therefore no "active Workspace" ambiguity to resolve for v1 — a Workspace-scoped claim is unambiguous the moment an `Account` has any Workspace membership at all, same as the already-shipped `WorkspaceRoleClaimsCustomizer` (BR-WS-06) already assumes for its own `workspace_id`/`workspace_role` claims. No login-flow change, no "choose your workspace" step. This ADR's own change to that customizer (§4) is a value swap only — `workspace_role` becomes the assigned `WorkspaceRole.name()` instead of the old enum's `.name()`, and a new `workspace_permissions` claim carries the effective, inheritance-resolved permission set — not a new selection mechanism.

If a genuine multi-Workspace-membership need surfaces later (v1.1+), an active-Workspace mechanism can be designed then, against a real requirement instead of a misread constraint.

### 5. Role deletion requires zero remaining references — reassignment or unassignment, consumer's choice

Deleting a `WorkspaceRole` is rejected while any `WorkspaceMembership` row still points at it (`roleId = that role's id`). It succeeds the moment none do — reached either of two ways, both explicit, no implicit cascade performed *by the delete itself*:

- **Reassign** each affected member to a different `WorkspaceRole` (`ChangeWorkspaceMemberRole`, unchanged shape from §1).
- **Unassign**: clear a member's `roleId` to `null` (a new, distinct action — not the same call as reassigning to another role) — an explicitly allowed state (§1): "member of the Workspace, no role currently assigned." A memberless-role membership carries no `workspace_permissions` (an empty effective set) until reassigned.

Either path is subject to the §2 invariant unchanged: an action that would leave a Workspace with zero memberships holding `clavaris:workspace:manage_members` is rejected regardless of which of the two paths above triggered it. The reserved role itself still can never be deleted (§1) — this section only concerns non-reserved, consumer-defined roles.

Automatic cascade *performed by the delete operation itself* (auto-reassign to the parent role, or to the reserved role, as part of one delete call) was considered and rejected: both would be a silent permission change for whichever member the cascade moves, exactly the kind of implicit side effect this codebase's own conventions avoid elsewhere (e.g. ADR-0010 §6.1's fixed anti-abuse thresholds, never silently loosened). Reassignment/unassignment stay their own explicit, auditable, webhook-emitting actions (`workspace_membership.role_changed`), performed before the delete is even attempted.

### 6. Migration

No production tenant depends on the `ADMIN`/`MEMBER` enum yet (CLAUDE.md §11) — this is a clean-break schema change:

1. New tables: `workspace_roles` (`id`, `organization_id`, `name`, `parent_role_id` nullable self-FK, `reserved`, `created_at`) and `workspace_role_permissions` (`role_id`, `permission` varchar — many-valued, same shape as an OAuth client's scope storage).
2. `workspace_memberships.role` (enum column) is dropped; `workspace_memberships.role_id` (FK) is added.
3. Any existing dev/test `Organization`s with `Workspace`s get two backfilled `WorkspaceRole` rows ("Admin" carrying both reserved permissions, "Member" carrying none) mapped from the old enum values, purely to keep existing seed/fixture data structurally valid through the cutover — not a designed migration path for real tenant data, since none exists yet.

## Consequences

- **Positive:** closes the actual product gap (arbitrary, per-consumer team hierarchies) without Clavaris ever encoding a single business-domain concept — the opacity discipline in §1 keeps this consistent with CLAUDE.md §1/§12.
- **Positive:** the hierarchy-with-inheritance mechanic (§3) is a genuine, generic differentiator over Clerk/WorkOS/Auth0's flat role-permission bundles, while staying just as opaque as they are.
- **Positive:** reuses the existing `client_credentials`-protected management API and webhook-module's existing event-delivery machinery — no new cross-cutting infrastructure needed, only new entities and endpoints inside `organization-module`.
- **Negative:** every one of `organization-module`'s existing Workspace use cases that reasoned about `WorkspaceRole.ADMIN`/`MEMBER` (`AddWorkspaceMemberService`, `ChangeWorkspaceMemberRoleService`, `RemoveWorkspaceMemberService`, `CreateWorkspaceService`, and their controllers/templates) needs to change from an enum comparison to a reserved-permission-set check — a real, non-trivial implementation cost, not a mechanical rename.
- **Negative:** `parentRoleId` cycle-checking and effective-permission resolution are new domain logic with their own edge cases (self-parenting, orphaned parent on delete) that need real test coverage, not just "add a column."
- **Negative:** this is a second re-litigation of the same subsystem ADR-0010 §3 already narrowed once (2026-08-27) — a real cost in documentation/design churn, accepted here because the product requirement driving it is real and the opacity-preserving design keeps it from being a scope creep back into business-domain territory.

## Alternatives considered

- **Layer custom roles beside `ADMIN`/`MEMBER` instead of replacing them** (two parallel role systems: a fixed system tier + a separate consumer-defined tier) — considered, **rejected per explicit decision**: one role system is simpler to reason about and to expose via token claims than two; the reserved-permission-namespace mechanism (§2) already gives Clavaris everything it needs to gate its own endpoints without a second enum.
- **Built-in semantic hierarchy primitives** (Clavaris understanding concepts like "approval level" or "seniority" natively, not just opaque strings) — **rejected**: directly violates CLAUDE.md §1's "no product-specific logic" founding constraint; the opaque-string-plus-generic-hierarchy-edge design (§1/§3) gets the useful mechanic (inheritance) without the semantic knowledge.
- **No hierarchy at all, flat roles only (pure Clerk parity)** — considered as the simpler v1 slice; **not chosen** as the final design because the product requirement explicitly asked to go further than Clerk, and `parentRoleId` + inheritance is a bounded, well-understood generalization (a DAG-free tree walk), not open-ended scope.

## Open questions

None remaining — the three raised in the initial draft (multi-workspace token claims, webhook event-type naming, role-deletion-while-assigned) were each reviewed and resolved above (§4.1, §4, §5 respectively) before this ADR moved past "Proposed."
