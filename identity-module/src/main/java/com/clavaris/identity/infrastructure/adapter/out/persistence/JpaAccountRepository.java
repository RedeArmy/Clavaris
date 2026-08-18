package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PasswordCredential;
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

    final PasswordCredential credential =
        account
            .passwordCredential()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Attempted to save an Account with no password credential attached — "
                            + "BR-ID-02 invariant violated before reaching persistence"));
    credentials.saveAndFlush(
        new PasswordCredentialEntity(
            credential.id(),
            credential.accountId().value(),
            credential.passwordHash(),
            credential.updatedAt()));
  }
}
