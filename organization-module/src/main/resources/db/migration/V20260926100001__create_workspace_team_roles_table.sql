-- ADR-0028 §2: the team<->role association — a join row, deliberately not a field on
-- workspace_roles itself, since workspace_roles is Organization-scoped (shared across every
-- Workspace an Organization owns) while workspace_teams is Workspace-scoped: "which team a role
-- is grouped under" can only be answered per-Workspace, never as one value on the role's own row.
--
-- No composite uniqueness beyond the primary key here — "at most one team per Workspace for a
-- given role" is enforced at the application layer (AddRoleToWorkspaceTeamService), not the
-- database: expressing it as a DB constraint would need workspace_team_roles to also carry
-- workspace_id redundantly, just to scope a unique index by it.
--
-- Both FKs cascade: a deleted team drops its own role associations (the roles themselves survive,
-- becoming ungrouped again); a deleted role drops its own team association the same way (team
-- grouping carries no invariant that would need an explicit rejection first, unlike
-- DeleteWorkspaceRoleService's own still-assigned-to-a-member/still-someone's-parent guards).
CREATE TABLE workspace_team_roles (
    workspace_team_id uuid NOT NULL REFERENCES workspace_teams (id) ON DELETE CASCADE,
    workspace_role_id uuid NOT NULL REFERENCES workspace_roles (id) ON DELETE CASCADE,
    PRIMARY KEY (workspace_team_id, workspace_role_id)
);

-- AddRoleToWorkspaceTeamService's own "is this role already in a different team in this
-- Workspace" check scans by workspace_role_id across every team; RemoveWorkspaceTeamMember's own
-- lookups key off workspace_team_id — both directions need their own index, the primary key above
-- only covers the (team, role) pair itself efficiently in that order.
CREATE INDEX ix_workspace_team_roles_workspace_role_id ON workspace_team_roles (workspace_role_id);
