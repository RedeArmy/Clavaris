package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformPasswordCredential;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port; mirrors {@link JpaAccountRepository} exactly, minus {@code
 * organizationId} scoping — including its TD-PERF-019 {@code insert}/{@code save} split.
 */
@Repository
class JpaPlatformAccountRepository implements PlatformAccountRepository {

  private final SpringDataPlatformAccountJpaRepository accounts;
  private final SpringDataPlatformPasswordCredentialJpaRepository credentials;
  private final EntityManager entityManager;

  /* package */ JpaPlatformAccountRepository(
      final SpringDataPlatformAccountJpaRepository accounts,
      final SpringDataPlatformPasswordCredentialJpaRepository credentials,
      final EntityManager entityManager) {
    this.accounts = accounts;
    this.credentials = credentials;
    this.entityManager = entityManager;
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

  /**
   * Code review finding (SDE-III design, Phase 2 #8): same fix as {@code JpaAccountRepository}'s
   * own identical {@code save()} — that class's own Javadoc/comment has the full reasoning. This
   * one was missed on the first pass (the exact class of divergence this whole session kept finding
   * between the tenant and platform tiers), found live when migration {@code V20260830110000}'s own
   * deferred trigger rejected several integration tests' own test-fixture helpers that call this
   * method directly, outside any {@code @Transactional} caller.
   *
   * <p>SDE-III review, 2026-09-03 — real bug found and closed: this method still unconditionally
   * required a {@code PlatformPasswordCredential} via {@code orElseThrow}, the exact same bug
   * {@code JpaAccountRepository}'s own sibling method was already fixed for (ADR-0020 Phase 6) — a
   * brand new social signup ({@code
   * AuthenticatePlatformAccountWithSocialProviderService#linkBrandNewAccount}) saves a {@code
   * PlatformAccount} with no password credential at all, relying on the same transaction's own
   * {@code PlatformSocialIdentity} insert to satisfy BR-ID-02 (migration {@code V20260830110000}'s
   * deferred trigger, platform-tier half). Every first-time "Sign in with Google/GitHub" against
   * {@code /platform/login/social/{provider}} threw this {@code IllegalStateException} uncaught, a
   * 500 on every attempt — this sibling repository never received the fix its tenant-tier twin got.
   * Only persist a row here if the aggregate actually carries one, same as {@code
   * JpaAccountRepository}.
   */
  @Override
  @Transactional
  public void save(final PlatformAccount account) {
    // saveAndFlush — same "the unique constraint must throw synchronously, inside the caller's
    // own try/catch" rationale as JpaAccountRepository's own identical call.
    accounts.saveAndFlush(toEntity(account));
    saveCredentialIfPresent(account, credentials::saveAndFlush);
  }

  // TD-PERF-019: same insert()/save() split as JpaAccountRepository, same two call sites
  // (RegisterPlatformAccountService, AuthenticatePlatformAccountWithSocialProviderService#
  // linkBrandNewAccount) that already know this PlatformAccount is genuinely new.
  @Override
  @Transactional
  public void insert(final PlatformAccount account) {
    entityManager.persist(toEntity(account));
    saveCredentialIfPresent(account, entityManager::persist);
    entityManager.flush();
  }

  private PlatformAccountEntity toEntity(final PlatformAccount account) {
    return new PlatformAccountEntity(
        account.id().value(),
        account.email().value(),
        account.emailVerifiedAt().orElse(null),
        account.status().name(),
        account.createdAt());
  }

  private void saveCredentialIfPresent(
      final PlatformAccount account, final Consumer<PlatformPasswordCredentialEntity> saver) {
    account
        .passwordCredential()
        .ifPresent(
            credential ->
                saver.accept(
                    new PlatformPasswordCredentialEntity(
                        credential.id(),
                        credential.platformAccountId().value(),
                        credential.passwordHash(),
                        credential.updatedAt())));
  }
}
