package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOAuthClientRepository}. {@code findByClientId} is also
 * the lookup path the per-Organization issuer's {@code RegisteredClientRepository} adapter (app
 * module) uses at token/authorize-request time.
 */
public interface OAuthClientRepository {

  void save(OAuthClient client);

  Optional<OAuthClient> findByClientId(String clientId);
}
