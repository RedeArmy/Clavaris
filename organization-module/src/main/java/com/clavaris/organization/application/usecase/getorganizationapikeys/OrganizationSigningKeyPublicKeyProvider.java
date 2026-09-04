package com.clavaris.organization.application.usecase.getorganizationapikeys;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — deliberately does not reference identity-module's {@code SigningKey}/{@code
 * KeyPair} types directly (module-independence rule, same convention {@code SigningKeyProvisioner}
 * already establishes). Implemented in {@code app} by {@code
 * OrganizationSigningKeyPublicKeyProviderBridge}, reading identity-module's already-existing {@code
 * OrganizationSigningKeyMaterialFactory#keyPairFor} — no new key-material machinery, only a new way
 * to read the public half of what already exists.
 */
@FunctionalInterface
public interface OrganizationSigningKeyPublicKeyProvider {

  /**
   * @return the Organization's own active signing key, PEM-encoded (X.509 SubjectPublicKeyInfo,
   *     {@code -----BEGIN PUBLIC KEY-----}) — empty only if the Organization has no active key at
   *     all, which BR-ORG-06 says should never happen for a real Organization.
   */
  Optional<String> pemPublicKeyFor(UUID organizationId);
}
