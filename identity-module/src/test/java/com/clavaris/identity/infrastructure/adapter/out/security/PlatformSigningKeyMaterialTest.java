package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

class PlatformSigningKeyMaterialTest {

  @Test
  void generatesARealRsa2048KeyPair() {
    PlatformSigningKeyMaterial material = new PlatformSigningKeyMaterial();

    assertThat(material.keyPair().getPublic()).isInstanceOf(RSAPublicKey.class);
    assertThat(material.keyPair().getPrivate()).isInstanceOf(RSAPrivateKey.class);
    assertThat(((RSAPublicKey) material.keyPair().getPublic()).getModulus().bitLength())
        .as("ADR-0002: RS256, 2048-bit minimum")
        .isGreaterThanOrEqualTo(2048);
  }

  @Test
  void assignsANonBlankKid() {
    PlatformSigningKeyMaterial material = new PlatformSigningKeyMaterial();

    assertThat(material.kid()).isNotBlank();
  }

  @Test
  void eachInstanceGetsItsOwnKeyPair_notAStub() {
    PlatformSigningKeyMaterial first = new PlatformSigningKeyMaterial();
    PlatformSigningKeyMaterial second = new PlatformSigningKeyMaterial();

    assertThat(first.kid()).isNotEqualTo(second.kid());
    assertThat(first.keyPair().getPublic()).isNotEqualTo(second.keyPair().getPublic());
  }
}
