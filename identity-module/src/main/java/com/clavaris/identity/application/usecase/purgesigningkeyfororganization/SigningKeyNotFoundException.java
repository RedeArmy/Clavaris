package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Covers both "this Organization never had a key with this kid" and "wrong organizationId" with the
 * same outcome — same "doesn't distinguish the two, by design" reasoning {@code
 * NoActiveSigningKeyException} already establishes for its own, narrower case.
 */
public final class SigningKeyNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SigningKeyNotFoundException(final OrganizationId organizationId, final String kid) {
    super("No SigningKey exists with kid " + kid + " for Organization " + organizationId.value());
  }
}
