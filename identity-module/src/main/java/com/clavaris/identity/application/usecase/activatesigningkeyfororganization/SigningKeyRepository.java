package com.clavaris.identity.application.usecase.activatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaSigningKeyRepository}.
 */
public interface SigningKeyRepository {

  Optional<SigningKey> findActive(OrganizationId organizationId);

  void save(SigningKey signingKey);
}
