package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PasswordCredential;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model} (framework-free) and the
 * {@code @Entity} classes in this package.
 */
@Repository
class JpaAccountRepository implements AccountRepository {

  private final SpringDataAccountJpaRepository accounts;
  private final SpringDataPasswordCredentialJpaRepository credentials;

  // Constructed only by Spring's own component scan (via @Repository above), never directly by
  // other code — AccountRepository (the port) is the only type callers outside this package
  // should depend on.
  /* package */ JpaAccountRepository(
      final SpringDataAccountJpaRepository accounts,
      final SpringDataPasswordCredentialJpaRepository credentials) {
    this.accounts = accounts;
    this.credentials = credentials;
  }

  @Override
  public boolean existsByOrganizationIdAndEmail(
      final OrganizationId organizationId, final Email email) {
    return accounts.existsByOrganizationIdAndEmail(organizationId.value(), email.value());
  }

  @Override
  public Optional<Account> findByOrganizationIdAndEmail(
      final OrganizationId organizationId, final Email email) {
    return accounts
        .findByOrganizationIdAndEmail(organizationId.value(), email.value())
        .map(this::toDomain);
  }

  @Override
  public Optional<Account> findById(final AccountId accountId) {
    return accounts.findById(accountId.value()).map(this::toDomain);
  }

  private Account toDomain(final AccountEntity entity) {
    final AccountId accountId = new AccountId(entity.getId());
    // A password-only login attempt against an account that only has a social identity attached
    // (not yet implemented) is exactly the case reconstitute's own Javadoc calls out — absent here
    // is not a bug, AuthenticateWithPasswordService treats it as "no password credential to check".
    final PasswordCredential credential =
        credentials
            .findByAccountId(entity.getId())
            .map(
                row ->
                    PasswordCredential.reconstitute(
                        row.getId(), accountId, row.getPasswordHash(), row.getUpdatedAt()))
            .orElse(null);
    return Account.reconstitute(
        accountId,
        new OrganizationId(entity.getOrganizationId()),
        new Email(entity.getEmail()),
        entity.getCreatedAt(),
        entity.getEmailVerifiedAt(),
        AccountStatus.valueOf(entity.getStatus()),
        credential);
  }

  @Override
  public void save(final Account account) {
    final AccountEntity entity =
        new AccountEntity(
            account.id().value(),
            account.organizationId().value(),
            account.email().value(),
            account.emailVerifiedAt().orElse(null),
            account.status().name(),
            account.createdAt());

    // saveAndFlush, not save: the unique constraint on accounts.(organization_id, email)
    // (data-model.md §3) must throw synchronously, right here, so RegisterAccountService's
    // try/catch actually catches it. Plain save() only stages the insert in the persistence
    // context — Hibernate would defer executing it until the surrounding @Transactional method
    // returns and the transaction commits, by which point the try/catch block has already
    // exited and the exception would surface somewhere the service never expects it.
    accounts.saveAndFlush(entity);

    // ADR-0020 (Phase 6, live-verified): a brand-new social signup (AuthenticateWithSocialProvider
    // Service#linkBrandNewAccount) saves an Account with no PasswordCredential at all — BR-ID-02's
    // real "never zero auth methods" invariant is upheld one level up, by that same transaction
    // also saving a SocialIdentity for the same account, not by this repository unconditionally
    // requiring a password specifically. Account.reconstitute's own Javadoc already documented a
    // null credential as a legitimate social-only state; this method previously still threw on it —
    // a real, previously-undetected gap this phase's own integration test caught live (a 500 on the
    // very first real social signup), not a hypothetical one. Only persist a row here if the
    // aggregate actually carries one.
    //
    // Code review finding: this removes the persistence layer's own fail-fast enforcement of
    // BR-ID-02 for every caller, not just the social-login ones — every current caller happens to
    // also save a SocialIdentity/PasswordCredential in the same transaction, so nothing breaks
    // today, but nothing here would catch a future regression that doesn't. A synchronous guard
    // can't work here regardless (see AccountAuthMethodIntegrityCheckJob's own Javadoc for why the
    // ordering makes that structurally impossible) — AccountAuthMethodIntegrityCheckJob is the
    // compensating control, a daily sweep that surfaces such a regression within 24h instead of
    // leaving it undetected indefinitely.
    account
        .passwordCredential()
        .ifPresent(
            credential ->
                credentials.saveAndFlush(
                    new PasswordCredentialEntity(
                        credential.id(),
                        credential.accountId().value(),
                        credential.passwordHash(),
                        credential.updatedAt())));
  }

  @Override
  public void deleteById(final AccountId accountId) {
    // No existence check or exception here on purpose — the caller (DeleteAccountService) already
    // did its own findById lookup before deciding to call this at all, and Spring Data's own
    // delete-by-id is a no-op, not an error, if the row somehow already gone.
    //
    // The explicit flush below matches the "must actually reach Postgres now, not whenever the
    // surrounding transaction happens to commit" reasoning as save()'s own saveAndFlush above — a
    // bare delete only stages the removal in the persistence context. Nothing in this class's own
    // production call path strictly depends on that timing today, but leaving it deferred is a
    // real footgun for any future caller (or test) that reads this same row through a different
    // connection/path within the same transaction, exactly the class of bug save()'s own comment
    // already flags.
    accounts.deleteById(accountId.value());
    accounts.flush();
  }

  @Override
  public void deleteAllByOrganizationId(final OrganizationId organizationId) {
    accounts.deleteAllByOrganizationId(organizationId.value());
    accounts.flush();
  }

  @Override
  public List<Account> findAllByOrganizationId(final OrganizationId organizationId) {
    return accounts.findByOrganizationId(organizationId.value()).stream()
        .map(this::toDomain)
        .toList();
  }
}
