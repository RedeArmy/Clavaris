package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.ActivateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.SigningKeyMaterialGenerator;
import com.clavaris.identity.domain.model.SigningKey;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link PurgeSigningKeyForOrganizationUseCase} — TD-SEC-029's own emergency,
 * zero-overlap purge, resolved. Replaces the raw-SQL fallback
 * (`incident-response-signing-key-compromise.md` §3.6 used to describe) with a real, audited
 * operation: {@code UPDATE signing_keys SET retired_at = '<a timestamp older than the overlap
 * window>' ...} becomes {@link SigningKey#purgeImmediately()}, called from here instead of by hand.
 *
 * <p>If the targeted {@code kid} is the Organization's own currently-active key, a replacement is
 * generated and activated first — reusing {@link ActivateSigningKeyForOrganizationUseCase}
 * unchanged, the exact same mechanism {@code RotateSigningKeyForOrganizationService} already uses
 * for ordinary rotation — so the Organization is never left without an active key even for the
 * instant between purging the old one and this transaction committing. That reused call retires the
 * old key with the <i>normal</i> overlap timestamp ("now"); this service's own {@link
 * SigningKey#purgeImmediately()} call, made afterward against the same in-memory row within the
 * same transaction, is the one whose value actually lands in the database — the normal retirement
 * is never independently persisted before being overridden.
 */
public class PurgeSigningKeyForOrganizationService
    implements PurgeSigningKeyForOrganizationUseCase {

  // ADR-0002 — same constant RotateSigningKeyForOrganizationService already hardcodes for the
  // same reason: nothing else is ever passed here.
  private static final String ALGORITHM = "RS256";

  private final SigningKeyRepository signingKeys;
  private final SigningKeyMaterialGenerator keyMaterial;
  private final ActivateSigningKeyForOrganizationUseCase activate;
  private final AuditEventRecorder auditEvents;

  public PurgeSigningKeyForOrganizationService(
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
  public PurgeSigningKeyForOrganizationResult handle(
      final PurgeSigningKeyForOrganizationCommand command) {
    final SigningKey target =
        signingKeys
            .findByKid(command.organizationId(), command.kid())
            .orElseThrow(
                () -> new SigningKeyNotFoundException(command.organizationId(), command.kid()));

    // Only the currently-active key needs a replacement — an already-retired kid (discovered
    // compromised after the fact) leaves the Organization's own separate active key untouched.
    final boolean wasActive = target.retiredAt().isEmpty();
    final String replacementKid;
    if (wasActive) {
      replacementKid = keyMaterial.generateFor(command.organizationId());
      activate.handle(command.organizationId(), replacementKid, ALGORITHM);
    } else {
      replacementKid = null;
    }

    target.purgeImmediately();
    signingKeys.save(target);

    auditEvents.write(
        command.actor(),
        "signing_key.emergency_purged",
        "Organization",
        command.organizationId().value().toString(),
        "purgedKid=" + command.kid() + " replacementKid=" + replacementKid);

    return new PurgeSigningKeyForOrganizationResult(
        command.organizationId(), command.kid(), replacementKid);
  }
}
