-- TD-SEC-003: backs Spring Authorization Server's own JdbcOAuth2AuthorizationService — every
-- authorization code, access token, refresh token, and OIDC ID token this system issues, across
-- both the platform tier and every Organization, replacing SAS's default in-memory
-- OAuth2AuthorizationService (lost on restart, invisible across more than one running instance).
--
-- This is the framework's own upstream schema (org/springframework/security/oauth2/server/
-- authorization/oauth2-authorization-schema.sql in the resolved spring-security-oauth2-
-- authorization-server-7.1.0 jar), adapted per that file's own embedded PostgreSQL instructions:
-- every `blob` column becomes `text`, every `timestamp` column becomes `timestamptz`. Column names,
-- widths, and nullability are otherwise copied verbatim — JdbcOAuth2AuthorizationService's SQL
-- (LOAD_AUTHORIZATION_SQL/SAVE_AUTHORIZATION_SQL/...) references these exact column names as
-- private, non-configurable constants, so this schema is a contract with that class, not a free
-- design choice.
--
-- One shared table for both the platform tier and every Organization — not two, unlike every other
-- platform/tenant pair in this codebase (platform_clients vs oauth_clients, platform_signing_keys
-- vs signing_keys). Confirmed via decompiling the resolved jar: JdbcOAuth2AuthorizationService's
-- own TABLE_NAME is a private static final constant hardcoding "oauth2_authorization" into every
-- SQL statement it runs — there is no supported way to point two instances of the stock class at
-- two differently-named tables. `registered_client_id` still fully disambiguates platform-tier rows
-- from Organization-tier rows (their RegisteredClientRepository implementations, and therefore the
-- IDs each service ever looks up, are already structurally disjoint), so this is a safe, documented
-- trade-off against forking/subclassing the framework's own well-tested class — not a silent gap.
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamptz DEFAULT NULL,
    access_token_expires_at timestamptz DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamptz DEFAULT NULL,
    oidc_id_token_expires_at timestamptz DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamptz DEFAULT NULL,
    refresh_token_expires_at timestamptz DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamptz DEFAULT NULL,
    user_code_expires_at timestamptz DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamptz DEFAULT NULL,
    device_code_expires_at timestamptz DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

-- Not in the framework's own baseline schema — added because BR-ID-03's reuse-detection chain and
-- ordinary token-exchange lookups both filter by these columns; the framework ships no index beyond
-- the primary key at all.
CREATE INDEX ix_oauth2_authorization_registered_client_id ON oauth2_authorization (registered_client_id);
CREATE INDEX ix_oauth2_authorization_principal_name ON oauth2_authorization (principal_name);
