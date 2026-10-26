-- redirect_uris, allowed_grant_types, allowed_scopes and post_logout_redirect_uris were text
-- columns holding a JSON array that only the application ever parsed (TD-ARCH-003). Spring
-- Authorization Server consumes them as Set<String> whatever the storage shape, so per-row child
-- tables would have bought the framework nothing; what the text type did cost was that the
-- database could neither validate the value nor answer "which clients allow this redirect URI?"
-- without a full scan and a LIKE over serialised JSON.
--
-- jsonb with a CHECK that the value is an array makes the shape a database guarantee, and a GIN
-- index (jsonb_path_ops, containment only) serves `redirect_uris @> '["https://app/cb"]'` for the
-- audit and takeover questions the register named as the trigger. The application keeps writing
-- the same JSON text; only the column type and its guarantees change.
--
-- ALTER ... TYPE rewrites the table under an ACCESS EXCLUSIVE lock. These tables hold one row per
-- registered client, so the rewrite is instantaneous in practice; on a deployment with many
-- thousands of clients run it in a quiet window.
ALTER TABLE oauth_clients
    ALTER COLUMN redirect_uris TYPE jsonb USING redirect_uris::jsonb,
    ALTER COLUMN allowed_grant_types TYPE jsonb USING allowed_grant_types::jsonb,
    ALTER COLUMN allowed_scopes TYPE jsonb USING allowed_scopes::jsonb,
    ALTER COLUMN post_logout_redirect_uris DROP DEFAULT,
    ALTER COLUMN post_logout_redirect_uris TYPE jsonb USING post_logout_redirect_uris::jsonb,
    ALTER COLUMN post_logout_redirect_uris SET DEFAULT '[]'::jsonb;

ALTER TABLE oauth_clients
    ADD CONSTRAINT ck_oauth_clients_redirect_uris_array CHECK (jsonb_typeof(redirect_uris) = 'array'),
    ADD CONSTRAINT ck_oauth_clients_allowed_grant_types_array CHECK (jsonb_typeof(allowed_grant_types) = 'array'),
    ADD CONSTRAINT ck_oauth_clients_allowed_scopes_array CHECK (jsonb_typeof(allowed_scopes) = 'array'),
    ADD CONSTRAINT ck_oauth_clients_post_logout_redirect_uris_array CHECK (jsonb_typeof(post_logout_redirect_uris) = 'array');

CREATE INDEX ix_oauth_clients_redirect_uris ON oauth_clients USING gin (redirect_uris jsonb_path_ops);
CREATE INDEX ix_oauth_clients_post_logout_redirect_uris ON oauth_clients USING gin (post_logout_redirect_uris jsonb_path_ops);

-- The same column on the two credential tables (ADR-0023).
ALTER TABLE organization_clients
    ALTER COLUMN allowed_scopes TYPE jsonb USING allowed_scopes::jsonb,
    ADD CONSTRAINT ck_organization_clients_allowed_scopes_array CHECK (jsonb_typeof(allowed_scopes) = 'array');

ALTER TABLE platform_clients
    ALTER COLUMN allowed_scopes TYPE jsonb USING allowed_scopes::jsonb,
    ADD CONSTRAINT ck_platform_clients_allowed_scopes_array CHECK (jsonb_typeof(allowed_scopes) = 'array');
