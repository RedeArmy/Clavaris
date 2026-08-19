package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

import com.clavaris.clientregistry.domain.model.PlatformClient;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPlatformClientRepository}. Also the lookup path the
 * platform issuer's {@code RegisteredClientRepository} adapter (app module, wired against Spring
 * Authorization Server) uses at token-request time — BR-PLATFORM-01.
 */
public interface PlatformClientRepository {

  boolean existsByClientId(String clientId);

  Optional<PlatformClient> findByClientId(String clientId);

  void save(PlatformClient platformClient);
}
