-- ADR-0028: a named, Workspace-scoped grouping label over a subset of that Workspace's own
-- Organization's workspace_roles. Purely organizational — carries no permission semantics of its
-- own, unlike workspace_roles itself.
--
-- workspace_id cascades: workspaces is this same module's own table, and a team has no meaning
-- once its own Workspace is gone.
CREATE TABLE workspace_teams (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         varchar(255) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- A consumer's own team names are opaque to Clavaris but still scoped for uniqueness within their
-- own Workspace — same "one namespace per scope" convention workspace_roles' own
-- ux_workspace_roles_organization_id_name already follows at a different scope.
CREATE UNIQUE INDEX ux_workspace_teams_workspace_id_name
    ON workspace_teams (workspace_id, name);

CREATE INDEX ix_workspace_teams_workspace_id ON workspace_teams (workspace_id);
