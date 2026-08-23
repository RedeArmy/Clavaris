package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * No point generating new key material to rotate to when there's nothing to rotate away from —
 * BR-ORG-06 guarantees every real Organization has an active key, so this also doubles as "no
 * Organization exists with this id" without a cross-module existence check (identity-module cannot
 * depend on organization-module's own repository — the same module-independence rule {@code
 * client-registry-module}'s own pom.xml already documents).
 */
public final class NoActiveSigningKeyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NoActiveSigningKeyException(final OrganizationId organizationId) {
    super("No active SigningKey exists for Organization " + organizationId.value());
  }
}
