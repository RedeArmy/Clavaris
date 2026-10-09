package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.app.infrastructure.adapter.out.bridge.SessionPolicyProviderBridge;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicySnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@link SessionPolicyProviderBridge}'s own delegate ({@code
 * GetSessionPolicyForOrganizationService}) is a plain, uncached read — and unlike {@code
 * AccountAuthenticationPolicyProvider} (read once or twice per login request), {@link
 * SessionPolicyProvider} is consulted on <b>every single refresh-token grant</b> ({@code
 * RotateRefreshTokenService}) as well as every login ({@code IssueRefreshTokenService}) — the
 * single highest-traffic path in the system (BR-ID-03's own description). Same short-TTL, bounded
 * read-through cache shape {@code CachingAccountAuthenticationPolicyProvider} already establishes
 * for the identical tradeoff: a session-policy write ({@code SetSessionPolicyController}/the
 * dashboard) is a rare, operator- or tenant-owner-triggered action, not a hot path worth optimizing
 * consistency for.
 *
 * <p>{@code @Primary}: this decorator, not {@link SessionPolicyProviderBridge} directly, is what
 * every real caller of {@link SessionPolicyProvider} now receives — same "decorator becomes the
 * default, real adapter reached only by qualifier" shape {@code
 * CachingAccountAuthenticationPolicyProvider}'s own Javadoc documents.
 */
@Component
@Primary
class CachingSessionPolicyProvider implements SessionPolicyProvider {

  private final SessionPolicyProvider delegate;
  private final Cache<OrganizationId, SessionPolicySnapshot> cache;

  /* package */ CachingSessionPolicyProvider(
      @Qualifier("sessionPolicyProviderBridge") final SessionPolicyProvider delegate,
      @Value("${clavaris.session.policy-cache-ttl-seconds:30}") final long ttlSeconds,
      // Same "real Organization count is expected to stay small, generous headroom rather than
      // a number this project expects to actually approach" reasoning as every sibling cache's
      // own identical bound.
      @Value("${clavaris.session.policy-cache-max-size:10000}") final long maxSize) {
    this.delegate = delegate;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .maximumSize(maxSize)
            .build();
  }

  @Override
  public SessionPolicySnapshot policyFor(final OrganizationId organizationId) {
    return cache.get(organizationId, delegate::policyFor);
  }

  // Test-only observability, same rationale as CachingAccountAuthenticationPolicyProvider's own
  // identical method.
  /* package */ long estimatedSizeAfterCleanup() {
    cache.cleanUp();
    return cache.estimatedSize();
  }
}
