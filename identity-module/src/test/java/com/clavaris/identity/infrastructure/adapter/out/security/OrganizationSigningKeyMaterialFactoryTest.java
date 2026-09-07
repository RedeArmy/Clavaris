package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
    factory.cacheActive(organizationId, kid);

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

    factory.cacheActive(first, factory.generateFor(first));
    factory.cacheActive(second, factory.generateFor(second));

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
    factory.cacheActive(organizationId, factory.generateFor(organizationId));
    KeyPair first = factory.keyPairFor(organizationId).orElseThrow();

    factory.cacheActive(organizationId, factory.generateFor(organizationId));
    KeyPair second = factory.keyPairFor(organizationId).orElseThrow();

    assertThat(second.getPublic()).isNotEqualTo(first.getPublic());
  }

  @Test
  void cacheActiveThrowsWhenTheKidHasNoMatchingKeyStoreEntry() {
    // TD-SEC-051: every real caller only ever passes a kid this same factory's own generateFor
    // just minted — a missing key store entry at this point is a real data-integrity violation,
    // never an expected outcome, so this must surface loudly rather than silently no-op.
    OrganizationSigningKeyMaterialFactory factory = newFactory();
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> factory.cacheActive(organizationId, "never-generated-kid"));
  }

  // TD-SEC-051: the actual regression this row exists to prevent — see
  // SigningKeyMaterialGenerator's own Javadoc for the full traced race. Models the exact
  // interleaving that used to break this cache: two concurrent "rotations" mint their own key
  // material in one order (generateFor(A) then generateFor(B)), but the DB-level advisory lock
  // — simulated here simply by which cacheActive call happens last — resolves the other way
  // (kid A ends up "active" last). Before this fix, the cache's own final state was determined by
  // generateFor's own call order (would have ended up holding B, the wrong key); after this fix,
  // it's determined entirely by cacheActive's own call order, matching whichever kid a real
  // Postgres advisory lock would have serialized as the true winner.
  @Test
  void cacheReflectsWhicheverKidCacheActiveWasCalledWithLastRegardlessOfGenerateForOrder() {
    OrganizationSigningKeyMaterialFactory factory = newFactory();
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

    String kidA = factory.generateFor(organizationId);
    String kidB = factory.generateFor(organizationId);
    // The DB-level winner (kid A) is the one activated — and therefore cached — LAST, even though
    // its own generateFor call happened FIRST in wall-clock time.
    factory.cacheActive(organizationId, kidB);
    factory.cacheActive(organizationId, kidA);

    // KeyPair itself has no equals() override (reference equality) — comparing the wrapped
    // RSAPublicKey instead is what every other identity-comparison in this test class already
    // does (see e.g. differentOrganizationsGetGenuinelyDifferentKeyPairs above), since RSA key
    // implementations do compare by their actual modulus/exponent.
    Optional<KeyPair> cached = factory.keyPairFor(organizationId);
    assertThat(cached).isPresent();
    assertThat(cached.orElseThrow().getPublic())
        .isEqualTo(factory.keyPairForKid(kidA).orElseThrow().getPublic());
    assertThat(cached.orElseThrow().getPublic())
        .isNotEqualTo(factory.keyPairForKid(kidB).orElseThrow().getPublic());
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
