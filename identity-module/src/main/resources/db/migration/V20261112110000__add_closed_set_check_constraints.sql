-- TD-ARCH-054: a CHECK on columns that hold one of a small closed set the Java enum owns. Until now
-- the values were right only because the code writing them was; a hand-written statement, a script
-- or a typo in a literal (the retention sweep and its partial index name 'SUCCEEDED' and
-- 'EXHAUSTED') could store anything and nothing would object.
--
-- NOT VALID: the constraint is enforced for every new write from now on, but the rows already in the
-- table are not read, so adding it takes its lock for an instant instead of for a table scan. The
-- next migration validates them, on its own, because validating in the same transaction would keep
-- this one's lock for the whole scan.
--
-- Open sets (event_type, aggregate_type, action, the OAuth2 grant strings) are deliberately not
-- constrained: they grow with the code and are not owned by one enum. The drift test
-- (EnumColumnConstraintsIntegrationTest) fails when an enum gains a value and its CHECK does not.

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED', 'PENDING_APPROVAL', 'REJECTED')) NOT VALID;
ALTER TABLE platform_accounts
    ADD CONSTRAINT ck_platform_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED', 'PENDING_APPROVAL', 'REJECTED')) NOT VALID;
ALTER TABLE social_identities
    ADD CONSTRAINT ck_social_identities_provider CHECK (provider IN ('GOOGLE', 'GITHUB')) NOT VALID;
ALTER TABLE platform_social_identities
    ADD CONSTRAINT ck_platform_social_identities_provider CHECK (provider IN ('GOOGLE', 'GITHUB')) NOT VALID;
ALTER TABLE pending_social_links
    ADD CONSTRAINT ck_pending_social_links_provider CHECK (provider IN ('GOOGLE', 'GITHUB')) NOT VALID;
ALTER TABLE pending_platform_social_links
    ADD CONSTRAINT ck_pending_platform_social_links_provider CHECK (provider IN ('GOOGLE', 'GITHUB')) NOT VALID;
ALTER TABLE verification_tokens
    ADD CONSTRAINT ck_verification_tokens_type CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EMAIL_SIGN_IN_CODE', 'EMAIL_SIGN_IN_LINK', 'DEVICE_TRUST_CHALLENGE', 'NEW_DEVICE_LOGIN_ALERT')) NOT VALID;
ALTER TABLE platform_verification_tokens
    ADD CONSTRAINT ck_platform_verification_tokens_type CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'EMAIL_SIGN_IN_CODE', 'EMAIL_SIGN_IN_LINK', 'DEVICE_TRUST_CHALLENGE', 'NEW_DEVICE_LOGIN_ALERT')) NOT VALID;
