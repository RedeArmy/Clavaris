-- TD-PERF-026: V20260926090001 added workspace_memberships.role_id with no supporting index,
-- unlike every sibling FK column elsewhere in this module (ix_workspace_roles_organization_id,
-- ix_workspace_teams_workspace_id, ix_workspace_team_roles_workspace_role_id).
-- WorkspaceMembershipRepository#existsByRoleId is invoked on every role deletion, unscoped across
-- the whole table — including the auto-unassign cascade from team deletion — and runs an
-- unindexed scan without this.
CREATE INDEX ix_workspace_memberships_role_id ON workspace_memberships (role_id);
