package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The same rule {@code OAuthClientEmbeddingEligibilityChecker} applies per client to the {@code
 * frame-ancestors} header (ADR-0009 §4, BR-CLIENT-04), asked per Organization because the session
 * cookie is per Organization: a development-environment Organization can be framed by any origin, a
 * production one only once some client has a {@code VERIFIED} custom domain with a registered
 * embedding origin. Keeping the two in step is the point — a cookie allowed cross-site for an
 * Organization whose pages the policy refuses to frame would be exposure with no purpose.
 *
 * <p>Cached briefly per Organization. The answer is needed only when a session cookie is written (a
 * sign-in, or the first page that needs a CSRF token), never when one is read, but the Organization
 * id comes from the URL, so the cache is bounded in size and an unknown id costs one indexed query,
 * not a growing map. A change takes at most the TTL to apply, and an Account's existing session
 * keeps the attribute it was written with until it ends: the first sign-in after an Organization
 * starts being embedded gets a cross-site cookie, an older session is asked to sign in again inside
 * the frame.
 */
@Component
class OrganizationEmbeddingSessionPolicy implements EmbeddingSessionPolicy {

  private final OrganizationEnvironmentChecker environments;
  private final ClientDomainConfigRepository domainConfigs;
  private final Cache<UUID, Boolean> answers;

  /* package */ OrganizationEmbeddingSessionPolicy(
      final OrganizationEnvironmentChecker environments,
      final ClientDomainConfigRepository domainConfigs,
      @Value("${clavaris.session.embedding-policy-cache-ttl-seconds:60}") final long ttlSeconds,
      @Value("${clavaris.session.embedding-policy-cache-max-size:10000}") final long maxSize) {
    this.environments = environments;
    this.domainConfigs = domainConfigs;
    this.answers =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .maximumSize(maxSize)
            .build();
  }

  @Override
  public boolean allowsCrossSiteSession(final UUID organizationId) {
    return answers.get(organizationId, this::computeFor);
  }

  private boolean computeFor(final UUID organizationId) {
    return environments.isDevelopment(organizationId)
        || domainConfigs.existsVerifiedEmbeddingByOrganizationId(organizationId);
  }
}
