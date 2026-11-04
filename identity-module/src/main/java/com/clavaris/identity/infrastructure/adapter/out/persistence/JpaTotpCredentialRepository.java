package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.TotpCredential;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** ADR-0040: the Postgres adapter of {@link TotpCredentialRepository}. */
@Repository
class JpaTotpCredentialRepository implements TotpCredentialRepository {

  private final SpringDataTotpCredentialJpaRepository credentials;

  /* package */ JpaTotpCredentialRepository(
      final SpringDataTotpCredentialJpaRepository credentials) {
    this.credentials = credentials;
  }

  @Override
  public Optional<TotpCredential> findByAccountId(final AccountId accountId) {
    return credentials
        .findByAccountId(accountId.value())
        .map(JpaTotpCredentialRepository::toDomain);
  }

  @Override
  public void save(final TotpCredential credential) {
    credentials.save(
        new TotpCredentialEntity(
            credential.id(),
            credential.accountId().value(),
            credential.organizationId().value(),
            credential.encryptedSecret(),
            credential.confirmedAt(),
            credential.lastUsedStep(),
            credential.createdAt()));
  }

  @Override
  public boolean existsConfirmed(final AccountId accountId) {
    return credentials.existsByAccountIdAndConfirmedAtIsNotNull(accountId.value());
  }

  @Override
  public boolean spendStep(final AccountId accountId, final long step) {
    return credentials.spendStep(accountId.value(), step) > 0;
  }

  @Override
  public boolean deleteByAccountId(final AccountId accountId) {
    return credentials.deleteByAccountId(accountId.value()) > 0;
  }

  private static TotpCredential toDomain(final TotpCredentialEntity entity) {
    return TotpCredential.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        new OrganizationId(entity.getOrganizationId()),
        entity.getSecretEncrypted(),
        entity.getConfirmedAt(),
        entity.getLastUsedStep(),
        entity.getCreatedAt());
  }
}
