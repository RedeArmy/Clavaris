-- Gives the audit events already written the tenant key added by V20261026110000 (TD-ARCH-038).
-- It lives in the app module because the Organization of an event is found in tables that belong to
-- several modules; the audit table itself belongs to common, which must not know about them.
--
-- Each statement handles one kind of target and only touches rows that have no Organization yet, so
-- the migration is a no-op on an empty table and safe to read in isolation. Rows whose target no
-- longer exists, and platform-level events, keep a null organization_id, as they would have been
-- written. target_id is free text, so every cast is behind the same UUID guard.

-- The Organization itself.
UPDATE audit_events
SET organization_id = target_id::uuid
WHERE organization_id IS NULL
  AND target_type = 'Organization'
  AND target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- Targets that carry their Organization directly.
UPDATE audit_events e SET organization_id = w.organization_id
FROM workspaces w
WHERE e.organization_id IS NULL AND e.target_type = 'Workspace'
  AND w.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = r.organization_id
FROM workspace_roles r
WHERE e.organization_id IS NULL AND e.target_type = 'WorkspaceRole'
  AND r.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = d.organization_id
FROM webhook_endpoints d
WHERE e.organization_id IS NULL AND e.target_type = 'WebhookEndpoint'
  AND d.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = d.organization_id
FROM webhook_deliveries d
WHERE e.organization_id IS NULL AND e.target_type = 'WebhookDelivery'
  AND d.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

-- An OAuth client is identified by its client_id string, not a UUID.
UPDATE audit_events e SET organization_id = c.organization_id
FROM oauth_clients c
WHERE e.organization_id IS NULL AND e.target_type = 'OAuthClient' AND c.client_id = e.target_id;

-- Targets that belong to a Workspace or an account, one join away.
UPDATE audit_events e SET organization_id = w.organization_id
FROM workspace_teams t JOIN workspaces w ON w.id = t.workspace_id
WHERE e.organization_id IS NULL AND e.target_type = 'WorkspaceTeam'
  AND t.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = w.organization_id
FROM workspace_memberships m JOIN workspaces w ON w.id = m.workspace_id
WHERE e.organization_id IS NULL AND e.target_type = 'WorkspaceMembership'
  AND m.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = a.organization_id
FROM accounts a
WHERE e.organization_id IS NULL AND e.target_type = 'Account'
  AND a.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = a.organization_id
FROM sessions s JOIN accounts a ON a.id = s.account_id
WHERE e.organization_id IS NULL AND e.target_type = 'Session'
  AND s.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = a.organization_id
FROM webauthn_credentials c JOIN accounts a ON a.id = c.account_id
WHERE e.organization_id IS NULL AND e.target_type = 'WebAuthnCredential'
  AND c.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;

UPDATE audit_events e SET organization_id = a.organization_id
FROM known_devices k JOIN accounts a ON a.id = k.account_id
WHERE e.organization_id IS NULL AND e.target_type = 'KnownDevice'
  AND k.id = CASE WHEN e.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                  THEN e.target_id::uuid END;
