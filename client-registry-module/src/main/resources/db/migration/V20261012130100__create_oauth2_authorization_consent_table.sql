-- Backs Spring Authorization Server's JdbcOAuth2AuthorizationConsentService: the scopes an
-- End-User has approved for a client, so a consent survives a restart and is shared by every
-- running instance (it used to live in memory and was asked for again after each restart).
--
-- This is the framework's own upstream schema (oauth2-authorization-consent-schema.sql in the
-- resolved spring-security-oauth2-authorization-server-7.1.0 jar), unchanged: the stock class
-- hardcodes these table and column names. `authorities` holds only scope names, no secrets.
-- Like oauth2_authorization, one table serves the platform tier and every Organization;
-- registered_client_id tells them apart.
CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

CREATE INDEX ix_oauth2_authorization_consent_principal_name
    ON oauth2_authorization_consent (principal_name);
