# ADR-0028: Workspace Teams (per-Workspace role grouping) and relaxing reserved-role deletion

**Status:** ✅ Approved (2026-09-26)

## Context

ADR-0027 shipped `WorkspaceRole` as an `Organization`-scoped entity — one role catalog shared across every `Workspace` an `Organization` owns, with an optional `parentRoleId` hierarchy and inherited effective permissions. It deliberately locked two invariants:

- The one system-seeded `reserved` role per `Organization` can **never be deleted**, regardless of assignment state (BR-WS-05/07) — enforced by `CannotDeleteReservedWorkspaceRoleException`, a `RESTRICT`-by-default FK, and asserted directly in tests.
- `WorkspaceRole` has no notion of a "team" or any other grouping narrower than the whole `Organization`.

A new requirement asks for both of these to change:

1. Role/hierarchy maintenance should happen from the Workspace-detail dashboard page (`/platform/dashboard/organizations/{organizationId}/workspaces/{workspaceId}`), organized into named **teams** (e.g. "QA" containing "Supervisor" → "Tester"), with some roles remaining ungrouped (e.g. "Admin", "Supervisor", "User" as standalone roles with no team).
2. A tenant must be able to delete the reserved "Admin" role, once it's no longer the Organization's only path to `manage_members`/`manage_roles`.
3. Assigning a member's role moves into a new "⋮" (3-dot) action menu on their row — the same `.clavaris-menu` component `identity-module`'s `organization-users.html` already uses — as an **added** item, alongside (not replacing) the existing inline role-change `<select>`.

Resolved by direct clarification (this ADR's own review) rather than assumed:

- **Team scope**: Teams are `Workspace`-scoped. `WorkspaceRole` stays `Organization`-scoped, unchanged from ADR-0027 — this ADR does not reopen that.
- **What a Team structurally is**: a lightweight label over a subset of the Organization's own `WorkspaceRole`s — not a second membership concept. A member's assignment is still just their `WorkspaceMembership.roleId`; which "team" that role happens to be grouped under, in a given Workspace's own view, is purely organizational, never a second thing a member is separately assigned to.
- **Reserved-role deletion**: allowed once at least one *other* role in the Organization already carries both reserved permissions (`clavaris:workspace:manage_members` and `clavaris:workspace:manage_roles`) — the Organization must always keep a path to self-governance, just no longer necessarily through the original seeded row.

## Decision

### 1. `WorkspaceTeam` — a new, `Workspace`-scoped entity

```
WorkspaceTeam
  id            UUID
  workspaceId   UUID    -- owning Workspace; teams do not span Workspaces
  name          String  -- consumer-defined, opaque to Clavaris (e.g. "QA")
  createdAt     Instant
```

Unique on `(workspace_id, name)` — same per-scope name-uniqueness convention `WorkspaceRole` already follows at the Organization level.

### 2. `WorkspaceTeamRole` — the team↔role association, a join row, not a field on `WorkspaceRole`

Because `WorkspaceRole` is `Organization`-scoped (shared across every `Workspace`) while `WorkspaceTeam` is `Workspace`-scoped, "this role belongs to this team" cannot be a plain column on `WorkspaceRole` itself — the same role could be grouped differently, or not at all, from one Workspace's own team view to another's, even though it's the exact same underlying role definition and permission set.

```
WorkspaceTeamRole
  workspaceTeamId  UUID  -- FK -> workspace_teams
  workspaceRoleId  UUID  -- FK -> workspace_roles (must belong to the team's own Workspace's Organization)
```

Primary key `(workspace_team_id, workspace_role_id)`. **Resolved during review**: a role may belong to at most one team per Workspace — enforced at the application layer (the two FKs alone don't express "at most one team per Workspace" without a third join dimension, since a role could in principle be grouped under teams in other Workspaces too, which is fine — the constraint is scoped to "within this one Workspace").

A role with no row in `WorkspaceTeamRole` for a given Workspace is simply ungrouped there — exactly the "Admin, Supervisor, User... no pertenecen a un grupo pero sí existen los roles" requirement. The existing `parentRoleId` hierarchy (ADR-0027 §3) is unchanged and orthogonal — a team groups roles for display/organization; the hierarchy (who inherits whose permissions) is still expressed the same way it already is, independent of team membership.

### 3. Reserved-role deletion — relaxed, not removed

`DeleteWorkspaceRoleService`'s unconditional `if (role.reserved()) throw CannotDeleteReservedWorkspaceRoleException` becomes conditional:

- Still rejected if any `WorkspaceMembership` references it, or any other role names it as `parentRoleId` (BR-WS-07/08, unchanged).
- **Newly allowed** once at least one *other* `WorkspaceRole` in the same Organization has *effective* permissions (via the existing `WorkspaceRoleHierarchy`) containing **both** `clavaris:workspace:manage_members` and `clavaris:workspace:manage_roles` — i.e., a real, already-defined substitute exists. If no such role exists yet, deletion is still rejected with the same exception as today.
- `reserved` itself is never transferred to another row — once deleted, the Organization simply has zero `reserved = true` rows going forward; nothing else depends on exactly one existing (`ManageMembersGuard`'s own "at least one `manage_members` holder" invariant is keyed off effective permissions, not the `reserved` flag).
- Editing the reserved role's permissions (`CannotStripReservedWorkspaceRolePermissionsException`) is **unchanged** — this ADR only relaxes deletion, not the existing "can't strip its own reserved permissions while it still is the reserved role" guard.

**Resolved during review**: this check only looks at role *definitions* (does a substitute role exist with the right permissions), not whether anyone actually *holds* it yet — the simpler, lighter-weight validation, confirmed explicitly over the stricter membership-coverage alternative.

### 4. Web UI — Workspace-detail page grows a Teams/Roles section

`workspace-detail.html` gains a new section (alongside the existing Members table) for this Workspace's own team/role view:

- One card per `WorkspaceTeam`, listing the roles grouped into it (with their existing parent/child hierarchy shown, e.g. indentation or a badge), plus create/rename/delete-team actions.
- One "Ungrouped roles" section listing every Organization role with no team association in this Workspace.
- Creating/editing a `WorkspaceRole` itself still happens on the existing Organization-level Configure → Workspace Roles page (unchanged, ADR-0027 Slice 5) — this page only manages **team grouping**, reusing the Organization's already-defined roles, plus the reserved-role delete relaxation from §3 (surfaced wherever role deletion already lives).

### 5. "Assign role" — new item on the existing "⋮" member-row menu

The Members table's existing inline role-change `<select>` + "Update role" button stay exactly as they are. A new `.clavaris-menu` (the same kebab-menu component `organization-users.html` already established) is added to each member row, with one item today — "Assign role" — which opens the same role-change action the inline `<select>` already offers (not a second, competing implementation; same use case, a second entry point). Future member actions (e.g. "Remove") could migrate into this menu later, but that's not part of this ADR.

## Consequences

- **Positive**: closes the real requirement (per-Workspace team organization, tenant self-governance without a permanently-undeletable seeded row) without reopening ADR-0027's Organization-level role sharing.
- **Positive**: `WorkspaceTeam`/`WorkspaceTeamRole` are pure organization/display concepts — no new permission semantics, no change to how effective permissions or the OIDC claims are computed.
- **Negative**: introduces a second grouping axis (`Organization`-scoped role catalog + `Workspace`-scoped team labels) that a future reader must keep straight — mitigated by keeping the join explicit (§2) rather than a field on `WorkspaceRole` that would silently only be correct for one Workspace.
- **Negative**: relaxing reserved-role deletion (§3) is a real security-posture change — an Organization can now end up self-administered entirely by roles it defined itself, with no system-seeded fallback. Mitigated by requiring a real substitute role to exist first, but see open question 2.

## Alternatives considered

- **Team as a field on `WorkspaceRole`** (`workspaceTeamId` column, nullable) — rejected: would make a role's team membership global across every Workspace it's used in, contradicting "Teams are per-Workspace" once the same role catalog is reused across more than one Workspace, which ADR-0027 already permits.
- **Keep the reserved role permanently undeletable, let the tenant build additional roles/hierarchy alongside it** — the "keep the invariant" option from this ADR's own review; not chosen because the user explicitly confirmed they want deletion allowed once a substitute exists.
- **Team as an independent entity with its own membership** (a user "in" a team separate from holding a team-scoped role) — rejected per direct clarification: adds a second membership concept for no confirmed need; the existing `WorkspaceMembership.roleId` plus a role's team-grouping label already covers the described use case.

## Open questions

None remaining — all three raised in the initial draft were reviewed and resolved during this ADR's own review, before it moved past "Proposed":

1. A `WorkspaceRole` may belong to **at most one** `WorkspaceTeam` per Workspace (§2) — not many-to-many.
2. The reserved-role deletion substitute check (§3) verifies a substitute role **definition** exists (right effective permissions), not that any real membership already holds it — the lighter-weight validation.
3. Managing a `WorkspaceTeam` (create/rename/delete, and its role associations) carries **no permission semantics of its own** — same access control as every other Workspace-detail action (an authenticated `PlatformAccount` that owns the Organization), no additional `manage_roles`-style gate.
