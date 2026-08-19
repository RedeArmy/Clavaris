package com.clavaris.identity.infrastructure.adapter.out.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

/**
 * ADR-0002 (RS256): shared RSA-2048 key-pair generation, used by both the platform issuer's
 * singleton key ({@link PlatformSigningKeyMaterial}, generated once at startup) and each
 * Organization's own key ({@link OrganizationSigningKeyMaterialFactory}, generated on demand at
 * {@code CreateOrganization} time) — same algorithm and key size, two different lifecycles, not
 * worth two copies of the same {@link KeyPairGenerator} boilerplate within one module.
 */
final class RsaKeyPairs {

  /**
   * ADR-0002: RS256 — 2048 bits is the minimum NIST-recommended RSA key size for this algorithm.
   */
  private static final int RSA_KEY_SIZE = 2048;

  private RsaKeyPairs() {
    // Static utility — not instantiable.
  }

  /* package */ static KeyPair generate() {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(RSA_KEY_SIZE);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      // RSA is a JDK-mandatory algorithm (every conforming JVM implementation provides it) — this
      // can only fail on a broken/non-conformant JVM, not as a normal runtime condition worth a
      // checked-exception-shaped recovery path.
      throw new IllegalStateException("RSA KeyPairGenerator not available on this JVM", e);
    }
  }
}
