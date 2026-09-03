package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformPasswordCredential;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; mirrors {@link JpaAccountRepository} exactly, minus {@code
 * organizationId} scoping.
 */
@Repository
class JpaPlatformAccountRepository implements PlatformAccountRepository {

  private final SpringDataPlatformAccountJpaRepository accounts;
  private final SpringDataPlatformPasswordCredentialJpaRepository credentials;

  /* package */ JpaPlatformAccountRepository(
      final SpringDataPlatformAccountJpaRepository accounts,
      final SpringDataPlatformPasswordCredentialJpaRepository credentials) {
    this.accounts = accounts;
    this.credentials = credentials;
  }

  @Override
  public boolean existsByEmail(final Email email) {
    return accounts.existsByEmail(email.value());
  }

  @Override
  public Optional<PlatformAccount> findByEmail(final Email email) {
    return accounts.findByEmail(email.value()).map(this::toDomain);
  }

  @Override
  public Optional<PlatformAccount> findById(final PlatformAccountId platformAccountId) {
    return accounts.findById(platformAccountId.value()).map(this::toDomain);
  }

  private PlatformAccount toDomain(final PlatformAccountEntity entity) {
    final PlatformAccountId platformAccountId = new PlatformAccountId(entity.getId());
    final PlatformPasswordCredential credential =
        credentials
            .findByPlatformAccountId(entity.getId())
            .map(
                row ->
                    PlatformPasswordCredential.reconstitute(
                        row.getId(), platformAccountId, row.getPasswordHash(), row.getUpdatedAt()))
            .orElse(null);
    return PlatformAccount.reconstitute(
        platformAccountId,
        new Email(entity.getEmail()),
        entity.getCreatedAt(),
        entity.getEmailVerifiedAt(),
        AccountStatus.valueOf(entity.getStatus()),
        credential);
  }

  // Code review finding (SDE-III design, Phase 2 #8): same fix as JpaAccountRepository's own
  // identical save() — that class's own Javadoc/comment has the full reasoning. This one was
  // missed on the first pass (the exact class of divergence this whole session kept finding
  // between the tenant and platform tiers), found live when migration V20260830110000's own
  // deferred trigger rejected several integration tests' own test-fixture helpers that call this
  // method directly, outside any @Transactional caller.
  //
  // SDE-III review, 2026-09-03 — real bug found and closed: this method still unconditionally
  // required a PlatformPasswordCredential via orElseThrow, the exact same bug
  // JpaAccountRepository's own sibling method was already fixed for (ADR-0020 Phase 6) — a brand
  // new social signup (AuthenticatePlatformAccountWithSocialProviderService#linkBrandNewAccount)
  // saves a PlatformAccount with no password credential at all, relying on the same transaction's
  // own PlatformSocialIdentity insert to satisfy BR-ID-02 (migration V20260830110000's deferred
  // trigger, platform-tier half). Every first-time "Sign in with Google/GitHub" against
  // /platform/login/social/{provider} threw this IllegalStateException uncaught, a 500 on every
  // attempt — this sibling repository never received the fix its tenant-tier twin got. Only
  // persist a row here if the aggregate actually carries one, same as JpaAccountRepository.
  @Override
  @Transactional
  public void save(final PlatformAccount account) {
    final PlatformAccountEntity entity =
        new PlatformAccountEntity(
            account.id().value(),
            account.email().value(),
            account.emailVerifiedAt().orElse(null),
            account.status().name(),
            account.createdAt());

    // saveAndFlush — same "the unique constraint must throw synchronously, inside the caller's
    // own try/catch" rationale as JpaAccountRepository's own identical call.
    accounts.saveAndFlush(entity);

    account
        .passwordCredential()
        .ifPresent(
            credential ->
                credentials.saveAndFlush(
                    new PlatformPasswordCredentialEntity(
                        credential.id(),
                        credential.platformAccountId().value(),
                        credential.passwordHash(),
                        credential.updatedAt())));
  }
}
