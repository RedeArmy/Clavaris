package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of the shared generator both {@link PlatformSigningKeyMaterial} and {@link
 * OrganizationSigningKeyMaterialFactory} delegate to — their own tests exercise it transitively,
 * but this asserts the ADR-0002 contract (RSA, 2048-bit minimum) at the source, not just via a
 * caller.
 */
class RsaKeyPairsTest {

  @Test
  void generatesARealRsa2048KeyPair() {
    KeyPair keyPair = RsaKeyPairs.generate();

    assertThat(keyPair.getPublic()).isInstanceOf(RSAPublicKey.class);
    assertThat(keyPair.getPrivate()).isInstanceOf(RSAPrivateKey.class);
    assertThat(((RSAPublicKey) keyPair.getPublic()).getModulus().bitLength())
        .isGreaterThanOrEqualTo(2048);
  }

  @Test
  void eachCallProducesAGenuinelyDifferentKeyPair() {
    KeyPair first = RsaKeyPairs.generate();
    KeyPair second = RsaKeyPairs.generate();

    assertThat(first.getPublic()).isNotEqualTo(second.getPublic());
  }
}
