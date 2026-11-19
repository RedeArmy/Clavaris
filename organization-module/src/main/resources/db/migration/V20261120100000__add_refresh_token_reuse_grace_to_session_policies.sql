-- TD-SEC-103 (ADR-0046): how long, in seconds, a refresh token that was rotated away may be
-- presented again without that counting as theft, for a client whose rotation response never
-- arrived. 0 is "off", the strict BR-ID-03 behaviour every Organization has had until now, and the
-- default, so an existing row changes nothing. The upper bound is the same 60 the application
-- enforces (SessionPolicy.MAX_REFRESH_TOKEN_REUSE_GRACE_SECONDS); the CHECK keeps a hand-edited row
-- from loosening it past that.
ALTER TABLE session_policies
    ADD COLUMN refresh_token_reuse_grace_seconds integer NOT NULL DEFAULT 0
        CONSTRAINT ck_session_policies_refresh_token_reuse_grace
            CHECK (refresh_token_reuse_grace_seconds BETWEEN 0 AND 60);
