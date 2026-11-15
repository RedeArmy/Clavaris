-- ADR-0043: PKCE becomes a per-client policy instead of a fixed rule (BR-CLIENT-03 read "every
-- client, always"). REQUIRED is what every client had until now, so it is the default for every
-- row already there and for any insert that does not say: nothing changes for an existing client,
-- and a client only ever becomes OPTIONAL through a deliberate, audited change by its owner.
--
-- The CHECK is part of the column because oauth_clients holds one row per registered application:
-- small enough that adding it needs no NOT VALID / VALIDATE split (TD-ARCH-054), and it keeps a
-- hand-written statement from storing a third value the Java enum does not know.
ALTER TABLE oauth_clients
    ADD COLUMN pkce_policy varchar(16) NOT NULL DEFAULT 'REQUIRED'
        CONSTRAINT ck_oauth_clients_pkce_policy CHECK (pkce_policy IN ('REQUIRED', 'OPTIONAL'));
