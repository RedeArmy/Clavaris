-- Code review finding (SDE-III design, Phase 2 #8): BR-ID-02's "never zero auth methods"
-- invariant was previously enforced only by AccountAuthMethodIntegrityCheckJob, a daily
-- @Scheduled sweep — the compensating control that class's own Javadoc explains was the only
-- option available, because a synchronous, mid-transaction guard structurally can't work: the FK
-- on social_identities.account_id forces the accounts row to exist before social_identities can,
-- so a check running at the accounts INSERT itself can never see a same-transaction
-- social_identities insert that hasn't happened yet.
--
-- A DEFERRABLE INITIALLY DEFERRED constraint trigger is Postgres's own native answer to exactly
-- this class of problem: it only fires at COMMIT, once every statement in the transaction (the
-- accounts insert AND its credential/identity insert, regardless of which ran first) has already
-- executed — real, synchronous, transactional enforcement (the whole transaction rolls back)
-- instead of a same-day-eventual daily alarm. Confirmed safe against every real account-creation
-- path in this codebase (RegisterAccountService, RegisterPlatformAccountService,
-- AuthenticateWithSocialProviderService#linkBrandNewAccount,
-- AuthenticatePlatformAccountWithSocialProviderService#linkBrandNewAccount — the only four
-- call sites of Account.register()/PlatformAccount.register() in the entire codebase) — every one
-- already attaches a credential/identity row inside the same @Transactional boundary.
--
-- AccountAuthMethodIntegrityCheckJob stays wired, deliberately not removed: a second, independent
-- safety net (e.g. a future raw-SQL admin script, or a migration that somehow drops this trigger)
-- would otherwise go undetected — belt and suspenders, not a replacement.
CREATE FUNCTION assert_account_has_auth_method() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM password_credentials WHERE account_id = NEW.id)
     AND NOT EXISTS (SELECT 1 FROM social_identities WHERE account_id = NEW.id) THEN
    RAISE EXCEPTION
        'BR-ID-02 violated: account % has no auth method (neither a password credential nor a linked social identity)',
        NEW.id;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_account_has_auth_method
    AFTER INSERT ON accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_account_has_auth_method();

-- Same invariant, same reasoning, for the platform tier's own equivalent tables.
CREATE FUNCTION assert_platform_account_has_auth_method() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
       SELECT 1 FROM platform_password_credentials WHERE platform_account_id = NEW.id)
     AND NOT EXISTS (
       SELECT 1 FROM platform_social_identities WHERE platform_account_id = NEW.id) THEN
    RAISE EXCEPTION
        'BR-ID-02 violated: platform_account % has no auth method (neither a password credential nor a linked social identity)',
        NEW.id;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_platform_account_has_auth_method
    AFTER INSERT ON platform_accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_platform_account_has_auth_method();
