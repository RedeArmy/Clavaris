-- TD-SEC-104 / ADR-0049: how long an access token issued to one client lives, in seconds. NULL is
-- the default (300, what every client had until now), so every row already there keeps its
-- behaviour and a client only changes it by a deliberate, audited act of its owner. The CHECK is
-- the same 60 to 3600 the application enforces (AccessTokenLifetime) and keeps a hand-written
-- statement from loosening it; oauth_clients is one row per registered application, small enough
-- that adding it needs no NOT VALID / VALIDATE split (TD-ARCH-054).
ALTER TABLE oauth_clients
    ADD COLUMN access_token_ttl_seconds integer
        CONSTRAINT ck_oauth_clients_access_token_ttl
            CHECK (access_token_ttl_seconds IS NULL OR access_token_ttl_seconds BETWEEN 60 AND 3600);
