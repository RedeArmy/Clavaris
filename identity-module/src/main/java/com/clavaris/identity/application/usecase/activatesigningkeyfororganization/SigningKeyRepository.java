package com.clavaris.identity.application.usecase.activatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaSigningKeyRepository}.
 */
public interface SigningKeyRepository {

  Optional<SigningKey> findActive(OrganizationId organizationId);

  /**
   * TD-SEC-008: every key JWKS must still publish for {@code organizationId} — the currently active
   * one, plus any retired key whose {@code retiredAt} is after {@code retiredAfter} (still within
   * the rotation overlap window a still-valid, pre-rotation token might have been signed under).
   * {@code retiredAfter} is the caller's own cutoff (now minus the configured overlap duration),
   * not a fixed value here — this port has no opinion on how long that window should be, {@code
   * OrganizationJwksPublishingSource} does.
   */
  List<SigningKey> findActiveAndRetiredSince(OrganizationId organizationId, Instant retiredAfter);

  void save(SigningKey signingKey);

  /**
   * BR-DATA-02/03's own organization-level equivalent — every key this Organization ever rotated
   * through, active or long-retired alike.
   */
  void deleteAllByOrganizationId(OrganizationId organizationId);
}
