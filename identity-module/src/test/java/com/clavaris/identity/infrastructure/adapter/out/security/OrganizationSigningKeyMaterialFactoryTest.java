package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrganizationSigningKeyMaterialFactoryTest {

  @TempDir private java.nio.file.Path tempDir;

  private SigningKeyStore newKeyStore() {
    return new SigningKeyStore(
        tempDir.resolve("org-signing-keys.p12").toString(), "a-test-key-store-password");
  }

  private SigningKeyRepository emptyRepository() {
    SigningKeyRepository repository = mock(SigningKeyRepository.class);
    when(repository.findActive(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    return repository;
  }

  private OrganizationSigningKeyMaterialFactory newFactory() {
    return new OrganizationSigningKeyMaterialFactory(emptyRepository(), newKeyStore());
  }

  @Test
  void generatesARealRsa2048KeyPairRetrievableByOrganizationId() {
    OrganizationSigningKeyMaterialFactory factory = newFactory();
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
  void isEmptyForAnOrganizationThatNeverHadAKeyGeneratedOrPersisted() {
    OrganizationSigningKeyMaterialFactory factory = newFactory();

    assertThat(factory.keyPairFor(new OrganizationId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void differentOrganizationsGetGenuinelyDifferentKeyPairs() {
    OrganizationSigningKeyMaterialFactory factory = newFactory();
    OrganizationId first = new OrganizationId(UUID.randomUUID());
    OrganizationId second = new OrganizationId(UUID.randomUUID());

    factory.generateFor(first);
    factory.generateFor(second);

    assertThat(factory.keyPairFor(first).orElseThrow().getPublic())
        .isNotEqualTo(factory.keyPairFor(second).orElseThrow().getPublic());
  }

  @Test
  void aSecondCallForTheSameOrganizationOverwritesThePreviousKey() {
    // Documented, known limitation (this class's own Javadoc) — asserted here so a future change
    // that accidentally starts retaining both keys (a real fix for the overlap requirement)
    // doesn't silently change this behaviour without a test noticing.
    OrganizationSigningKeyMaterialFactory factory = newFactory();
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    factory.generateFor(organizationId);
    KeyPair first = factory.keyPairFor(organizationId).orElseThrow();

    factory.generateFor(organizationId);
    KeyPair second = factory.keyPairFor(organizationId).orElseThrow();

    assertThat(second.getPublic()).isNotEqualTo(first.getPublic());
  }

  @Test
  void reloadsPersistedKeyMaterialAfterTheInMemoryCacheIsLost() {
    // TD-SEC-002's actual point: a brand-new factory instance (simulating a process restart, its
    // ConcurrentHashMap empty again) must still resolve a key it never itself generated, purely
    // from the repository's active row plus the key store — not just from its own cache.
    SigningKeyStore keyStore = newKeyStore();
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    KeyPair generatedBeforeRestart = keyStore.generate("persisted-org-kid");

    SigningKeyRepository repository = mock(SigningKeyRepository.class);
    when(repository.findActive(organizationId))
        .thenReturn(Optional.of(SigningKey.activate(organizationId, "persisted-org-kid", "RS256")));

    OrganizationSigningKeyMaterialFactory afterRestart =
        new OrganizationSigningKeyMaterialFactory(repository, keyStore);

    Optional<KeyPair> reloaded = afterRestart.keyPairFor(organizationId);

    assertThat(reloaded).isPresent();
    assertThat(reloaded.get().getPublic()).isEqualTo(generatedBeforeRestart.getPublic());
    assertThat(reloaded.get().getPrivate()).isEqualTo(generatedBeforeRestart.getPrivate());
  }

  @Test
  void isEmptyWhenTheActiveRowHasNoMatchingKeyStoreEntry() {
    SigningKeyRepository repository = mock(SigningKeyRepository.class);
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    when(repository.findActive(organizationId))
        .thenReturn(Optional.of(SigningKey.activate(organizationId, "orphaned-org-kid", "RS256")));

    OrganizationSigningKeyMaterialFactory factory =
        new OrganizationSigningKeyMaterialFactory(repository, newKeyStore());

    assertThat(factory.keyPairFor(organizationId)).isEmpty();
  }
}
