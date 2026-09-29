package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
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
   * TD-SEC-058: every currently-{@code VERIFIED} config, org-unscoped — the periodic
   * re-verification job (only real caller) sweeps across every tenant on a schedule, not per
   * request.
   */
  List<ClientDomainConfig> findAllVerified();

  void save(ClientDomainConfig config);
}
