package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.SigningKey;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-SEC-008/ADR-0010 §5.2: manually-triggered, audited signing-key rotation. Generates fresh key
 * material, then reuses {@link ActivateSigningKeyForOrganizationUseCase} unchanged — the exact same
 * "retire the currently active key, activate the new one" operation {@code CreateOrganization}
 * already calls once, at provisioning time (that use case's own Javadoc already named this as its
 * designed reuse case).
 *
 * <p>Does NOT, itself, close the "JWKS must keep serving the retired key" half of overlap — that's
 * {@code OrganizationJwksPublishingSource}'s job (app module), reading every still-in-window key
 * {@link SigningKeyRepository#findActiveAndRetiredSince} now returns. This service's own
 * responsibility ends at correctly retiring the old row and activating the new one; it doesn't need
 * to know how JWKS is served to do that correctly.
 */
public class RotateSigningKeyForOrganizationService
    implements RotateSigningKeyForOrganizationUseCase {

  // ADR-0002 — the only algorithm this codebase issues signing keys under, platform or
  // per-Organization (same constant CreateOrganizationSigningKeyBridge already hardcodes for the
  // same reason: nothing else is ever passed here).
  private static final String ALGORITHM = "RS256";

  private final SigningKeyRepository signingKeys;
  private final SigningKeyMaterialGenerator keyMaterial;
  private final ActivateSigningKeyForOrganizationUseCase activate;
  private final AuditEventRecorder auditEvents;

  public RotateSigningKeyForOrganizationService(
      final SigningKeyRepository signingKeys,
      final SigningKeyMaterialGenerator keyMaterial,
      final ActivateSigningKeyForOrganizationUseCase activate,
      final AuditEventRecorder auditEvents) {
    this.signingKeys = signingKeys;
    this.keyMaterial = keyMaterial;
    this.activate = activate;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public RotateSigningKeyForOrganizationResult handle(
      final RotateSigningKeyForOrganizationCommand command) {
    // BR-ORG-06 guarantees a real Organization always has an active key — an empty result here
    // means either a bogus organizationId or a broken invariant, either way nothing to rotate.
    // See NoActiveSigningKeyException's own Javadoc for why this also stands in for a real
    // existence check without a cross-module dependency on organization-module.
    final SigningKey currentlyActive =
        signingKeys
            .findActive(command.organizationId())
            .orElseThrow(() -> new NoActiveSigningKeyException(command.organizationId()));

    final String newKid = keyMaterial.generateFor(command.organizationId());
    final SigningKey rotated = activate.handle(command.organizationId(), newKid, ALGORITHM);

    auditEvents.write(
        command.actor(),
        "signing_key.rotated",
        "Organization",
        command.organizationId().value().toString(),
        "newKid=" + newKid + " previousKid=" + currentlyActive.kid());

    return new RotateSigningKeyForOrganizationResult(rotated, currentlyActive.kid());
  }
}
