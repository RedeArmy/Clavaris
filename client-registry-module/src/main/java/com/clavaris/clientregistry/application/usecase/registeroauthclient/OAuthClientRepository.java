package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOAuthClientRepository}. {@code findByClientId} is also
 * the lookup path the per-Organization issuer's {@code RegisteredClientRepository} adapter (app
 * module) uses at token/authorize-request time.
 */
public interface OAuthClientRepository {

  void save(OAuthClient client);

  Optional<OAuthClient> findByClientId(String clientId);

  // TD-SEC-010 (closed): JdbcOAuth2AuthorizationService (TD-SEC-003) reconstructs a
  // RegisteredClient
  // by its own internal id, not clientId, whenever it reloads a persisted OAuth2Authorization row —
  // OrganizationRegisteredClientRepository.findById needs this to stop being an unconditional
  // UnsupportedOperationException the moment authorization state actually persists.
  // "id", not "clientId" — this looks up the entity's own primary key, matching
  // RegisteredClientRepository.findById's own parameter naming (the SPI this ultimately serves).
  @SuppressWarnings("PMD.ShortVariable")
  Optional<OAuthClient> findById(UUID id);
}
