-- ADR-0009 §3: one-to-one, optional theming data for an OAuthClient's hosted login/consent pages.
-- Real FK + ON DELETE CASCADE: oauth_clients is owned by this same module's own Flyway migrations,
-- same ordering guarantee redirect_policies' own migration already documents.
--
-- Absence of a row for a given OAuthClient means "use Clavaris's own default look," not
-- "misconfigured" — same convention redirect_policies already establishes for its own
-- absence-of-row case.
CREATE TABLE client_brandings (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    oauth_client_id           uuid NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    logo_url                  text,
    primary_color             varchar(7),
    application_display_name  varchar(100),
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);

-- One branding row per OAuthClient — the "define vs. update in place" distinction
-- SetClientBrandingService itself enforces relies on this being unique.
CREATE UNIQUE INDEX ux_client_brandings_oauth_client_id ON client_brandings (oauth_client_id);
