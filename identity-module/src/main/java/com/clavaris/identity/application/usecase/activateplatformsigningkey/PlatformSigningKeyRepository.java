package com.clavaris.identity.application.usecase.activateplatformsigningkey;

import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPlatformSigningKeyRepository}.
 */
public interface PlatformSigningKeyRepository {

  Optional<PlatformSigningKey> findActive();

  void save(PlatformSigningKey signingKey);
}
