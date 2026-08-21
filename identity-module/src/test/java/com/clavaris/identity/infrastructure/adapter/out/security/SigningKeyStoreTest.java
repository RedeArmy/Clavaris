package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyStoreTest {

  @TempDir private java.nio.file.Path tempDir;

  private SigningKeyStore newStore() {
    return new SigningKeyStore(
        tempDir.resolve("signing-keys.p12").toString(), "a-test-key-store-password");
  }

  @Test
  void generatesAndRetrievesAKeyPairByKid() {
    SigningKeyStore store = newStore();
    String kid = UUID.randomUUID().toString();

    KeyPair generated = store.generate(kid);
    Optional<KeyPair> found = store.find(kid);

    assertThat(found).isPresent();
    assertThat(found.get().getPublic()).isEqualTo(generated.getPublic());
    assertThat(found.get().getPrivate()).isEqualTo(generated.getPrivate());
  }

  @Test
  void isEmptyForAKidNeverWritten() {
    SigningKeyStore store = newStore();

    assertThat(store.find(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void keyMaterialSurvivesANewStoreInstancePointedAtTheSameFile() {
    // TD-SEC-002's actual point: a process restart constructs a brand-new SigningKeyStore, not
    // the same in-memory instance — this is the empirical proof that a restart no longer loses
    // key material, not just that the same instance can read back what it just wrote.
    String kid = UUID.randomUUID().toString();
    KeyPair generated = newStore().generate(kid);

    SigningKeyStore reloadedAfterRestart = newStore();
    Optional<KeyPair> found = reloadedAfterRestart.find(kid);

    assertThat(found).isPresent();
    assertThat(found.get().getPublic()).isEqualTo(generated.getPublic());
    assertThat(found.get().getPrivate()).isEqualTo(generated.getPrivate());
  }

  @Test
  void storesMultipleKeysUnderDifferentKidsWithoutOverwritingEachOther() {
    SigningKeyStore store = newStore();
    String firstKid = UUID.randomUUID().toString();
    String secondKid = UUID.randomUUID().toString();

    KeyPair first = store.generate(firstKid);
    KeyPair second = store.generate(secondKid);

    // KeyPair itself has no equals() override (identity-only) — comparing the individual keys is
    // what actually proves each alias round-trips its own distinct material undisturbed by the
    // other, not just that .find() returns *some* non-empty Optional.
    assertThat(store.find(firstKid).orElseThrow().getPublic()).isEqualTo(first.getPublic());
    assertThat(store.find(firstKid).orElseThrow().getPrivate()).isEqualTo(first.getPrivate());
    assertThat(store.find(secondKid).orElseThrow().getPublic()).isEqualTo(second.getPublic());
    assertThat(store.find(secondKid).orElseThrow().getPrivate()).isEqualTo(second.getPrivate());
    assertThat(first.getPublic()).isNotEqualTo(second.getPublic());
  }

  @Test
  void aWrongPasswordCannotReadAnExistingStore() {
    String kid = UUID.randomUUID().toString();
    java.nio.file.Path path = tempDir.resolve("signing-keys.p12");
    new SigningKeyStore(path.toString(), "the-real-password").generate(kid);

    SigningKeyStore wrongPassword = new SigningKeyStore(path.toString(), "not-the-real-password");

    // PKCS12's own integrity check (HMAC over the file) fails to load under the wrong password —
    // confirmed live against the real JDK provider before this class was written, not assumed.
    assertThatThrownBy(() -> wrongPassword.find(kid)).isInstanceOf(IllegalStateException.class);
  }
}
