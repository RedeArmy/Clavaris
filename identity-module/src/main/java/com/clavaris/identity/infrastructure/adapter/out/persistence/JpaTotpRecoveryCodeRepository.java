package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** ADR-0040: the Postgres adapter of {@link TotpRecoveryCodeRepository}. */
@Repository
class JpaTotpRecoveryCodeRepository implements TotpRecoveryCodeRepository {

  private final SpringDataTotpRecoveryCodeJpaRepository codes;

  /* package */ JpaTotpRecoveryCodeRepository(final SpringDataTotpRecoveryCodeJpaRepository codes) {
    this.codes = codes;
  }

  @Override
  public void replaceAll(
      final AccountId accountId, final OrganizationId organizationId, final List<String> hashes) {
    codes.deleteByAccountId(accountId.value());
    final Instant now = Instant.now();
    codes.saveAll(
        hashes.stream()
            .map(
                hash ->
                    new TotpRecoveryCodeEntity(
                        UUID.randomUUID(), accountId.value(), organizationId.value(), hash, now))
            .toList());
  }

  @Override
  public boolean consume(final AccountId accountId, final String codeHash) {
    return codes.consume(accountId.value(), codeHash, Instant.now()) > 0;
  }

  @Override
  public long countUnused(final AccountId accountId) {
    return codes.countByAccountIdAndUsedAtIsNull(accountId.value());
  }

  @Override
  public void deleteAll(final AccountId accountId) {
    codes.deleteByAccountId(accountId.value());
  }
}
