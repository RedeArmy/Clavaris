# ADR-0029: Cross-module "Assign role" popup, Workspace-role display, and search/pagination on the Users tab

**Status:** ✅ Approved (2026-09-26)

## Context

The Organization-level "Users" tab (`organization-users.html`, `identity-module`'s `PlatformAccountsController`) lists every `Account` in an Organization — name, email, username, phone, last-signed-in, joined, and a `.clavaris-menu` (⋮) of Account-lifecycle actions (view profile, view log, impersonate, lock/ban, delete). It has no search, no configurable page size beyond the shared `KeysetPageRequest.DEFAULT_SIZE = 20`, and no visibility into a user's `WorkspaceRole` at all — that only exists on `organization-module`'s own `workspace-detail.html` (ADR-0027/0028).

A new requirement asks for, from this same Users tab:

1. A working search by name, username, or email.
2. Pagination at 25 rows per page (not the shared default of 20).
3. A visible "Role" column per Account.
4. Replacing the ADR-0028 §5 "Assign role" menu item's plain jump-link (to the Workspace-detail page's own inline `<select>`) with a real popup: a Team dropdown (shown only if the Workspace has at least one `WorkspaceTeam`), then a Role dropdown (scoped to that team, or every ungrouped role if no team is selected/none exist), and a Save action.

This directly reopens ADR-0028 §5's "additive, not a second implementation" decision — confirmed explicitly during this ADR's own review, not assumed.

**The real design problem is module boundaries, not UI.** `identity-module` and `organization-module` have **zero Maven dependency on each other** — both depend only on `common`. `WorkspaceMembership`/`WorkspaceRole`/`WorkspaceTeam` are entirely `organization-module`'s own concepts; `identity-module` must never import their Java types (§7.2). `webhook-module` already established the precedent for how a module reads data it doesn't own: its **own** read-side JPA entity mapped against another module's table, treated as a data contract — never the owning module's Java type, never a migration it doesn't own.

Also relevant: `AddWorkspaceMemberService`'s own Javadoc documents that v1 has no "attach an existing Account to a second Workspace" flow — every `Account` today has **at most one** `WorkspaceMembership`, empirically, not DB-enforced (no unique constraint on `account_id` alone in `workspace_memberships`). Design must not silently break if that ever changes.

## Decision

### 1. Search + pagination — `identity-module` only, no cross-module concern

- `ListAccountsForOrganizationQuery` gains an optional `searchTerm` (nullable — no filter when absent, same "absence is a no-op" convention `RateLimitPolicy` already follows). `SpringDataAccountJpaRepository`'s three keyset `@Query` methods (first/after/before) each gain a matching `AND (:searchTerm IS NULL OR LOWER(a.name) LIKE ... OR LOWER(a.email) LIKE ... OR LOWER(a.username) LIKE ...)` predicate — kept as one added clause per query, not a rewrite, so the existing keyset seek predicate is untouched.
- Page size: `KeysetPageRequest` gains a `fromCursors(String after, String before, int size)` overload; the existing 2-arg `fromCursors` delegates to it with `DEFAULT_SIZE`, unchanged for the other five dashboard lists. `PlatformAccountsController.showList` calls the new 3-arg overload with `25`, this page only.
- A plain `<input type="search">` bound to a new `q` request param, submitted via the page's existing HTMX pattern (same fragment-vs-redirect posture the rest of the dashboard already uses) — a search resets to the first page, same as changing any other pagination-affecting input would.

### 2. Role display + Workspace resolution — read-only projection, mirroring the outbox precedent

`identity-module` adds its **own** read-only JPA entity/repository mapped against `organization-module`'s existing `workspace_memberships` and `workspace_roles` tables (no new migration — `identity-module` owns no columns there, exactly like `webhook-module`'s outbox readers). One batched query, keyed by the current page's account ids, resolves `accountId → (workspaceId, roleId, roleName)` for the Role column — a single query per page render, not N+1.

If an account has more than one `WorkspaceMembership` row (not possible today, but not DB-prevented either) the projection takes the first and the Role column shows that one — a defensive, not a silent-crash, choice; nothing here assumes the invariant holds forever.

### 3. The "Assign role" popup itself — served entirely by `organization-module`, composed by HTTP, not by Java import

A new `organization-module` controller (`PlatformAccountWorkspaceRoleController`, distinct from `PlatformWorkspaceController` since its resource path is keyed by `accountId`, not `workspaceId`) exposes:

- `GET /platform/dashboard/organizations/{organizationId}/accounts/{accountId}/assign-role` — resolves the account's own `WorkspaceMembership` (via a new `FindWorkspaceMembershipForAccountUseCase`, org-scoped `findAllByAccountId`, taking the first result per §Context), then renders a fragment: a Team `<select>` (present only if `ListWorkspaceTeamsForWorkspaceUseCase` returns at least one team for that Workspace) and a Role `<select>`, pre-grouped by team in the initial HTML (each `<option>` carries a `data-team-id`), filtered client-side on team-select `change` — same "grouped picker, no second round trip" convention the Secret-Key scope picker and webhook event-type picker already established (`app/src/main/resources/static/js/scope-picker.js`), reused as a new sibling script, not a new pattern.
- `POST` on the same path — re-resolves and re-validates the Workspace/account ownership the same way `PlatformWorkspaceController.changeRole` already does, then delegates straight to the existing `ChangeWorkspaceMemberRoleUseCase` (no new mutation logic, no bypass of `CannotDemoteLastAdminException`/audit/outbox — same use case ADR-0027/0028 already built and tested).

`organization-users.html`'s "Assign role" menu item becomes `data-dialog-open` + `hx-get` into a shared `<dialog>`'s body with `hx-trigger="click once"` — the exact `manage-account-dialog` pattern `dashboard-nav.html` already uses for "Manage account" (`organization-dialog.js`, already loaded on every dashboard page). **No Maven dependency is added between the two modules** — the dialog's content is a normal HTTP response from `organization-module`'s own already-public controller layer, loaded into a page `identity-module` renders, the same way any two independently-deployable services would compose a UI, just without the network hop.

### 4. Consequence for ADR-0028 §5

Superseded, not deleted: the inline `<select>` + "Update role" button on `workspace-detail.html`'s Members table stays exactly as-is (it's `ChangeWorkspaceMemberRoleUseCase`'s original, still-valid entry point for someone already on that page). Only the **Users tab's own** "Assign role" item changes from "jump to that page" to "open this popup here" — `workspace-detail.html` itself is untouched by this ADR.

## Consequences

- **Positive**: closes the requirement without a single new Maven edge in the module graph — `identity-module`→`organization-module` stays at zero, same as today; the read-only projection and the HTTP-composed dialog are the only two new cross-module touchpoints, both already-precedented patterns (outbox reader, `manage-account-dialog`), not novel mechanisms.
- **Positive**: the popup can never bypass `ChangeWorkspaceMemberRoleUseCase`'s own invariants (last-admin guard, audit, outbox) — it's the same use case, called from a second controller, not a parallel write path.
- **Negative**: the Role-column projection duplicates a small amount of read logic that would be a one-liner if `identity-module` could just call `organization-module`'s repository directly — accepted, same tradeoff the outbox pattern already made project-wide.
- **Negative**: if v1's "one `WorkspaceMembership` per Account" assumption ever stops holding, the Role column and the popup's Workspace resolution both silently pick "the first one found" rather than surfacing "which Workspace?" — acceptable for v1 (documented, not silently wrong), revisit if/when a second-Workspace-membership flow ships.

## Alternatives considered

- **Give `identity-module` a Maven dependency on `organization-module`** (call its use cases directly in-process) — rejected: no precedent for a direct inter-module Java dependency anywhere in this codebase; the outbox-table-as-data-contract pattern exists specifically to avoid this, and reusing it here keeps the module graph's only established discipline intact.
- **Move the whole Users tab into `organization-module`** — rejected: most of its existing actions (lock/ban/delete/impersonate) are `identity-module`'s own `Account` lifecycle, not `organization-module`'s concern; moving the page would invert ownership of unrelated functionality just to solve one column and one popup.
- **A second, duplicate role-assignment write path inside `identity-module`'s own controller** (writing to `workspace_memberships` directly via its own read/write projection) — rejected: would bypass `ChangeWorkspaceMemberRoleUseCase`'s business rules (last-admin protection) and its audit/outbox side effects; the read-only projection in §2 is deliberately read-only for this exact reason.

## Open questions

None — search/pagination scope, the popup's Team-then-Role shape, and the decision to reopen ADR-0028 §5 for the Users tab specifically (leaving `workspace-detail.html`'s own inline control untouched) were all confirmed directly with the requester before this ADR was written.
