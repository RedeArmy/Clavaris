package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.AfterCommitExecutor;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-049: the write side of the account-authentication-policy cache. {@code
 * CachingAccountAuthenticationPolicyProvider} reads the policy through a bridge and holds it for a
 * TTL; when an operator changes it here, the entry is dropped on this instance and on every other
 * one once the transaction has committed, instead of every instance serving the old policy until
 * its own TTL runs out.
 *
 * <p>{@code @Primary}, the same decoration {@code CachingRateLimitPolicyRepository} uses: the use
 * cases keep depending on the port and never know.
 */
@Component
@Primary
class InvalidatingAccountAuthenticationPolicyRepository
    implements AccountAuthenticationPolicyRepository {

  private final AccountAuthenticationPolicyRepository delegate;
  private final CacheInvalidationBus invalidations;
  private final AfterCommitExecutor afterCommit;

  /* package */ InvalidatingAccountAuthenticationPolicyRepository(
      @Qualifier("jpaAccountAuthenticationPolicyRepository")
          final AccountAuthenticationPolicyRepository delegate,
      final CacheInvalidationBus invalidations,
      final AfterCommitExecutor afterCommit) {
    this.delegate = delegate;
    this.invalidations = invalidations;
    this.afterCommit = afterCommit;
  }

  @Override
  public Optional<AccountAuthenticationPolicy> findByOrganizationId(final UUID organizationId) {
    return delegate.findByOrganizationId(organizationId);
  }

  @Override
  public void save(final AccountAuthenticationPolicy policy) {
    delegate.save(policy);
    // After the commit: an instance that dropped its entry and re-read before it would load the old
    // policy and keep it for a full TTL.
    afterCommit.run(
        () ->
            invalidations.invalidate(
                CacheInvalidationBus.ACCOUNT_AUTHENTICATION_POLICY,
                policy.organizationId().toString()));
  }
}
