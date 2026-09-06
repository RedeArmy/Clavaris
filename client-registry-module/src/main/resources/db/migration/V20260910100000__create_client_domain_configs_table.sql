-- ADR-0009 §2: an OAuthClient's custom-domain registration and DNS TXT-record ownership challenge
-- (BR-CLIENT-04) — the mandatory prerequisite for embedding a production client's hosted login in
-- an iframe. Real FK + ON DELETE CASCADE: oauth_clients is owned by this same module's own Flyway
-- migrations, same ordering guarantee redirect_policies/client_brandings already document.
--
-- Absence of a row for a given OAuthClient means "SHARED mode" (Clavaris's own default host,
-- dev-only for embedding) — a real, valid, distinct state of its own, not "misconfigured."
CREATE TABLE client_domain_configs (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    oauth_client_id          uuid NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    mode                     varchar(16),
    hostname                 varchar(253),
    verification_status      varchar(16),
    dns_txt_challenge_token  varchar(64),
    -- ADR-0009 §4: the consumer's own frontend origin allowed to embed this client's hosted login
    -- in an iframe — independent of the DNS ownership challenge above (see ClientDomainConfig's
    -- own withEmbeddingOrigin Javadoc for why changing it never resets verification_status).
    embedding_origin         varchar(253),
    verified_at              timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now()
);

-- One domain-config row per OAuthClient — the "request vs. re-request in place" distinction
-- RequestClientDomainConfigService itself enforces relies on this being unique.
CREATE UNIQUE INDEX ux_client_domain_configs_oauth_client_id ON client_domain_configs (oauth_client_id);

-- BR-CLIENT-04 / STRIDE custom-domain-takeover defence: a hostname is claimed system-wide, never
-- shared by two OAuthClients (even across different Organizations) — see
-- HostnameAlreadyClaimedException's own Javadoc for why the DNS-level ownership proof alone isn't
-- enough to allow that.
CREATE UNIQUE INDEX ux_client_domain_configs_hostname ON client_domain_configs (hostname)
    WHERE hostname IS NOT NULL;
