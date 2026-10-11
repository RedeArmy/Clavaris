-- Per-provider settings of an Organization's social connection (the dashboard's "Connections" page):
-- whether to refuse sign-ins whose email uses a subaddress (name+tag@, name=tag@, name#tag@), and
-- whether the provider should always show its account chooser.
--
-- One optional row per (organization, provider). A missing row means the behaviour that existed
-- before these settings did: nothing blocked, no forced account chooser, so no Organization changes
-- behaviour until someone saves its connection.
CREATE TABLE organization_social_connection_settings (
    id                            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id               uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    provider                      varchar(20) NOT NULL,
    block_email_subaddresses      boolean NOT NULL,
    always_show_account_selector  boolean NOT NULL,
    created_at                    timestamptz NOT NULL DEFAULT now(),
    updated_at                    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, provider)
);
