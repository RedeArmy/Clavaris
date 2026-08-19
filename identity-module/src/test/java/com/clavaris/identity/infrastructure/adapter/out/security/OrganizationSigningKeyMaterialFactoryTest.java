package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.OrganizationId;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationSigningKeyMaterialFactoryTest {

  private final OrganizationSigningKeyMaterialFactory factory =
      new OrganizationSigningKeyMaterialFactory();

  @Test
  void generatesARealRsa2048KeyPairRetrievableByOrganizationId() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

    String kid = factory.generateFor(organizationId);

    assertThat(kid).isNotBlank();
    Optional<KeyPair> keyPair = factory.keyPairFor(organizationId);
    assertThat(keyPair).isPresent();
    assertThat(keyPair.get().getPublic()).isInstanceOf(RSAPublicKey.class);
    assertThat(keyPair.get().getPrivate()).isInstanceOf(RSAPrivateKey.class);
    assertThat(((RSAPublicKey) keyPair.get().getPublic()).getModulus().bitLength())
        .as("ADR-0002: RS256, 2048-bit minimum")
        .isGreaterThanOrEqualTo(2048);
  }

  @Test
  void isEmptyForAnOrganizationThatNeverHadAKeyGenerated() {
    assertThat(factory.keyPairFor(new OrganizationId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void differentOrganizationsGetGenuinelyDifferentKeyPairs() {
    OrganizationId first = new OrganizationId(UUID.randomUUID());
    OrganizationId second = new OrganizationId(UUID.randomUUID());

    factory.generateFor(first);
    factory.generateFor(second);

    assertThat(factory.keyPairFor(first)).isNotEqualTo(factory.keyPairFor(second));
  }

  @Test
  void aSecondCallForTheSameOrganizationOverwritesThePreviousKey() {
    // Documented, known limitation (this class's own Javadoc) — asserted here so a future change
    // that accidentally starts retaining both keys (a real fix for the overlap requirement,
    // CLAUDE.md §6) doesn't silently change this behaviour without a test noticing.
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    factory.generateFor(organizationId);
    KeyPair first = factory.keyPairFor(organizationId).orElseThrow();

    factory.generateFor(organizationId);
    KeyPair second = factory.keyPairFor(organizationId).orElseThrow();

    assertThat(second.getPublic()).isNotEqualTo(first.getPublic());
  }
}
