package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * TD-PERF-015: every primary-factor login controller ({@code LoginController} and its three
 * siblings) reads {@link AccountAuthenticationPolicySnapshot} twice per request — once inside its
 * own {@code Authenticate*UseCase}, again immediately after to build {@code DeviceTrustGate}'s
 * argument — and {@link AccountAuthenticationPolicyProviderBridge}'s own delegate ({@code
 * GetAccountAuthenticationPolicyForOrganizationService}) was a plain, uncached read on every single
 * call, unlike the structurally identical {@link
 * com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository}
 * (already wrapped by {@link CachingRateLimitPolicyRepository}, TD-PERF-006/TD-FUT-012). Same fix
 * shape here, deliberately — a short-TTL, bounded read-through cache, not a fully-consistent
 * invalidation scheme: {@code SetAccountAuthenticationPolicyController} writes are rare,
 * operator-only actions, not a hot path worth optimizing consistency for, the exact tradeoff {@link
 * CachingRateLimitPolicyRepository}'s own Javadoc already accepts for the identical shape of write.
 *
 * <p>{@code @Primary}: this decorator, not {@link AccountAuthenticationPolicyProviderBridge}
 * directly, is what every real caller of {@link AccountAuthenticationPolicyProvider} now receives —
 * same "decorator becomes the default, real adapter reached only by qualifier" shape {@link
 * CachingRateLimitPolicyRepository}'s own Javadoc documents for its identical situation.
 */
@Component
@Primary
class CachingAccountAuthenticationPolicyProvider implements AccountAuthenticationPolicyProvider {

  private final AccountAuthenticationPolicyProvider delegate;
  private final Cache<OrganizationId, AccountAuthenticationPolicySnapshot> cache;

  /* package */ CachingAccountAuthenticationPolicyProvider(
      @Qualifier("accountAuthenticationPolicyProviderBridge")
          final AccountAuthenticationPolicyProvider delegate,
      @Value("${clavaris.auth.policy-cache-ttl-seconds:30}") final long ttlSeconds,
      // TD-PERF-006's own identical reasoning: real Organization count is expected to stay small
      // for the foreseeable future — generous headroom, not a number this project expects to
      // actually approach, while still being a real, finite bound instead of none.
      @Value("${clavaris.auth.policy-cache-max-size:10000}") final long maxSize) {
    this.delegate = delegate;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .maximumSize(maxSize)
            .build();
  }

  @Override
  public AccountAuthenticationPolicySnapshot policyFor(final OrganizationId organizationId) {
    return cache.get(organizationId, delegate::policyFor);
  }

  // Test-only observability, same rationale as CachingRateLimitPolicyRepository's own identical
  // method — proves TD-PERF-006/TD-PERF-015's own actual point (a real, bounded cache) directly.
  /* package */ long estimatedSizeAfterCleanup() {
    cache.cleanUp();
    return cache.estimatedSize();
  }
}
