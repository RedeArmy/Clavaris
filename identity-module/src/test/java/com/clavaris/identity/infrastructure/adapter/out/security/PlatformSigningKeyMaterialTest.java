package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.activateplatformsigningkey.PlatformSigningKeyRepository;
import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformSigningKeyMaterialTest {

  @TempDir private java.nio.file.Path tempDir;

  private SigningKeyStore newKeyStore() {
    return new SigningKeyStore(
        tempDir.resolve("platform-signing-keys.p12").toString(), "a-test-key-store-password");
  }

  private PlatformSigningKeyRepository emptyRepository() {
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    when(repository.findActive()).thenReturn(Optional.empty());
    return repository;
  }

  @Test
  void generatesARealRsa2048KeyPairWhenNoKeyWasPreviouslyActive() {
    PlatformSigningKeyMaterial material =
        new PlatformSigningKeyMaterial(emptyRepository(), newKeyStore());

    assertThat(material.keyPair().getPublic()).isInstanceOf(RSAPublicKey.class);
    assertThat(material.keyPair().getPrivate()).isInstanceOf(RSAPrivateKey.class);
    assertThat(((RSAPublicKey) material.keyPair().getPublic()).getModulus().bitLength())
        .as("ADR-0002: RS256, 2048-bit minimum")
        .isGreaterThanOrEqualTo(2048);
  }

  @Test
  void assignsANonBlankKidWhenNoKeyWasPreviouslyActive() {
    PlatformSigningKeyMaterial material =
        new PlatformSigningKeyMaterial(emptyRepository(), newKeyStore());

    assertThat(material.kid()).isNotBlank();
  }

  @Test
  void eachFreshInstanceGetsItsOwnKeyPair_notAStub() {
    SigningKeyStore keyStore = newKeyStore();
    PlatformSigningKeyMaterial first = new PlatformSigningKeyMaterial(emptyRepository(), keyStore);
    PlatformSigningKeyMaterial second = new PlatformSigningKeyMaterial(emptyRepository(), keyStore);

    assertThat(first.kid()).isNotEqualTo(second.kid());
    assertThat(first.keyPair().getPublic()).isNotEqualTo(second.keyPair().getPublic());
  }

  @Test
  void reusesThePersistedKeyMaterialWhenAnActiveRowAndAMatchingKeyStoreEntryBothExist() {
    // TD-SEC-002's actual point: simulate a process restart by constructing a brand-new
    // SigningKeyStore pointed at the same file, plus a repository reporting the same active
    // kid a previous process would have persisted to Postgres — the new bean instance must
    // reload that exact key material, not generate a fresh one.
    SigningKeyStore beforeRestart = newKeyStore();
    KeyPair generatedBeforeRestart = beforeRestart.generate("persisted-kid");

    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    when(repository.findActive())
        .thenReturn(Optional.of(PlatformSigningKey.activate("persisted-kid", "RS256")));

    PlatformSigningKeyMaterial afterRestart =
        new PlatformSigningKeyMaterial(repository, newKeyStore());

    assertThat(afterRestart.kid()).isEqualTo("persisted-kid");
    assertThat(afterRestart.keyPair().getPublic()).isEqualTo(generatedBeforeRestart.getPublic());
    assertThat(afterRestart.keyPair().getPrivate()).isEqualTo(generatedBeforeRestart.getPrivate());
  }

  @Test
  void generatesAFreshKeyWhenTheActiveRowHasNoMatchingKeyStoreEntry() {
    // E.g. the compromise-response procedure in incident-response-signing-key-compromise.md §3:
    // an operator deletes the keystore entry (or the whole file) without also clearing the DB
    // row — this must still force fresh generation rather than throwing or silently signing with
    // nothing.
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    when(repository.findActive())
        .thenReturn(Optional.of(PlatformSigningKey.activate("orphaned-kid", "RS256")));

    PlatformSigningKeyMaterial material = new PlatformSigningKeyMaterial(repository, newKeyStore());

    assertThat(material.kid()).isNotEqualTo("orphaned-kid");
    assertThat(material.keyPair().getPublic()).isInstanceOf(RSAPublicKey.class);
  }
}
