package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — {@code save} doubles as insert-or-update, same convention every sibling port here
 * follows.
 */
public interface ClientDomainConfigRepository {

  Optional<ClientDomainConfig> findByOAuthClientId(UUID oauthClientId);

  /**
   * Hostnames are unique system-wide (see {@link HostnameAlreadyClaimedException}) — this is the
   * check {@link RequestClientDomainConfigService} runs before ever accepting a request, so it
   * needs to find a claim by hostname alone, not scoped to one {@code OAuthClient}.
   */
  Optional<ClientDomainConfig> findByHostname(String hostname);

  /**
   * TD-SEC-058, TD-PERF-044: the {@code VERIFIED} configs whose last verification is older than
   * {@code cutoff} (or was never stamped), stalest first, at most {@code limit} — org-unscoped, for
   * the periodic re-verification job, which sweeps a bounded batch per run rather than loading
   * every tenant's configs at once.
   */
  List<ClientDomainConfig> findStalestVerified(Instant cutoff, int limit);

  /**
   * Whether any client of this Organization has a {@code VERIFIED} custom domain with a registered
   * embedding origin — the production half of ADR-0009 §4's embedding-eligibility rule
   * (BR-CLIENT-04), asked per Organization rather than per client. The session cookie is per
   * Organization, so this is what decides whether that cookie must be allowed on cross-site
   * requests at all.
   */
  boolean existsVerifiedEmbeddingByOrganizationId(UUID organizationId);

  void save(ClientDomainConfig config);
}
