-- Clerk "customize redirect URLs" parity, adapted to Clavaris's own OIDC-first architecture: the
-- per-OAuthClient post-authentication landing-page configuration, used only when there is no
-- in-flight /oauth2/authorize request to resume (see RedirectPolicy's own Javadoc). A real FK +
-- ON DELETE CASCADE: oauth_clients is owned by this same module's own Flyway migrations, so their
-- relative ordering is guaranteed — same reasoning account_authentication_policies' own migration
-- documents for its FK to organizations.
--
-- Absence of a row for a given OAuthClient means "fall straight through to the platform's own
-- hardcoded default," not "misconfigured" — same convention rate_limit_policies/
-- account_authentication_policies already establish for their own absence-of-row case.
CREATE TABLE redirect_policies (
    id                            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    oauth_client_id               uuid NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    fallback_sign_in_redirect_url text,
    fallback_sign_up_redirect_url text,
    force_sign_in_redirect_url    text,
    force_sign_up_redirect_url    text,
    created_at                    timestamptz NOT NULL DEFAULT now(),
    updated_at                    timestamptz NOT NULL DEFAULT now()
);

-- One policy per OAuthClient — the "define vs. update in place" distinction
-- SetRedirectPolicyForClientService itself enforces relies on this being unique.
CREATE UNIQUE INDEX ux_redirect_policies_oauth_client_id ON redirect_policies (oauth_client_id);
