package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

import com.clavaris.clientregistry.domain.model.PlatformClient;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPlatformClientRepository}. Also the lookup path the
 * platform issuer's {@code RegisteredClientRepository} adapter (app module, wired against Spring
 * Authorization Server) uses at token-request time — BR-PLATFORM-01.
 */
public interface PlatformClientRepository {

  boolean existsByClientId(String clientId);

  Optional<PlatformClient> findByClientId(String clientId);

  // TD-SEC-010 (closed): JdbcOAuth2AuthorizationService (TD-SEC-003) reconstructs a
  // RegisteredClient
  // by its own internal id, not clientId, whenever it reloads a persisted OAuth2Authorization row —
  // PlatformRegisteredClientRepository.findById needs this to stop being an unconditional
  // UnsupportedOperationException the moment authorization state actually persists.
  // "id", not "clientId" — this looks up the entity's own primary key, matching
  // RegisteredClientRepository.findById's own parameter naming (the SPI this ultimately serves).
  @SuppressWarnings("PMD.ShortVariable")
  Optional<PlatformClient> findById(UUID id);

  void save(PlatformClient platformClient);
}
