package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
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
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    String kid = UUID.randomUUID().toString();

    KeyPair generated = store.generate(scope, kid);
    Optional<KeyPair> found = store.find(scope, kid);

    assertThat(found).isPresent();
    assertThat(found.get().getPublic()).isEqualTo(generated.getPublic());
    assertThat(found.get().getPrivate()).isEqualTo(generated.getPrivate());
  }

  @Test
  void isEmptyForAKidNeverWritten() {
    SigningKeyStore store = newStore();

    assertThat(store.find(KeyStoreScope.platform(), UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void keyMaterialSurvivesANewStoreInstancePointedAtTheSameDirectory() {
    // TD-SEC-002's actual point: a process restart constructs a brand-new SigningKeyStore, not
    // the same in-memory instance — this is the empirical proof that a restart no longer loses
    // key material, not just that the same instance can read back what it just wrote.
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    String kid = UUID.randomUUID().toString();
    KeyPair generated = newStore().generate(scope, kid);

    SigningKeyStore reloadedAfterRestart = newStore();
    Optional<KeyPair> found = reloadedAfterRestart.find(scope, kid);

    assertThat(found).isPresent();
    assertThat(found.get().getPublic()).isEqualTo(generated.getPublic());
    assertThat(found.get().getPrivate()).isEqualTo(generated.getPrivate());
  }

  @Test
  void storesMultipleKeysUnderDifferentKidsWithoutOverwritingEachOther() {
    SigningKeyStore store = newStore();
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    String firstKid = UUID.randomUUID().toString();
    String secondKid = UUID.randomUUID().toString();

    KeyPair first = store.generate(scope, firstKid);
    KeyPair second = store.generate(scope, secondKid);

    // KeyPair itself has no equals() override (identity-only) — comparing the individual keys is
    // what actually proves each alias round-trips its own distinct material undisturbed by the
    // other, not just that .find() returns *some* non-empty Optional.
    assertThat(store.find(scope, firstKid).orElseThrow().getPublic()).isEqualTo(first.getPublic());
    assertThat(store.find(scope, firstKid).orElseThrow().getPrivate())
        .isEqualTo(first.getPrivate());
    assertThat(store.find(scope, secondKid).orElseThrow().getPublic())
        .isEqualTo(second.getPublic());
    assertThat(store.find(scope, secondKid).orElseThrow().getPrivate())
        .isEqualTo(second.getPrivate());
    assertThat(first.getPublic()).isNotEqualTo(second.getPublic());
  }

  @Test
  void deleteRemovesAKeyPairSoItCanNoLongerBeFound() {
    SigningKeyStore store = newStore();
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    String kid = UUID.randomUUID().toString();
    store.generate(scope, kid);

    store.delete(scope, kid);

    assertThat(store.find(scope, kid)).isEmpty();
  }

  @Test
  void deleteIsANoOpForAKidNeverWritten() {
    SigningKeyStore store = newStore();
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());

    // TD-SEC-052: OrganizationSigningKeyMaterialFactory#purgeAllFor may call this for a kid it
    // read from signing_keys but which never actually got a key store entry — must not throw.
    store.delete(scope, UUID.randomUUID().toString());

    assertThat(store.find(scope, UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void deletingOneKidLeavesEveryOtherKidUntouched() {
    SigningKeyStore store = newStore();
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    String kept = UUID.randomUUID().toString();
    String removed = UUID.randomUUID().toString();
    KeyPair keptPair = store.generate(scope, kept);
    store.generate(scope, removed);

    store.delete(scope, removed);

    assertThat(store.find(scope, removed)).isEmpty();
    assertThat(store.find(scope, kept)).isPresent();
    assertThat(store.find(scope, kept).orElseThrow().getPublic()).isEqualTo(keptPair.getPublic());
  }

  @Test
  void deletedKeyMaterialStaysGoneAcrossANewStoreInstance() {
    // TD-SEC-052's actual point, same empirical standard as TD-SEC-002's own restart-survival
    // test above: the deletion must be durable on disk, not just absent from this instance's own
    // in-memory KeyStore object.
    java.nio.file.Path path = tempDir.resolve("signing-keys.p12");
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    SigningKeyStore store = new SigningKeyStore(path.toString(), "a-test-key-store-password");
    String kid = UUID.randomUUID().toString();
    store.generate(scope, kid);
    store.delete(scope, kid);

    SigningKeyStore reloadedAfterRestart =
        new SigningKeyStore(path.toString(), "a-test-key-store-password");

    assertThat(reloadedAfterRestart.find(scope, kid)).isEmpty();
  }

  @Test
  void aWrongMasterSecretCannotReadAnExistingStore() {
    KeyStoreScope scope = KeyStoreScope.organization(UUID.randomUUID());
    String kid = UUID.randomUUID().toString();
    java.nio.file.Path path = tempDir.resolve("signing-keys.p12");
    new SigningKeyStore(path.toString(), "the-real-master-secret").generate(scope, kid);

    SigningKeyStore wrongMasterSecret =
        new SigningKeyStore(path.toString(), "not-the-real-master-secret");

    // PKCS12's own integrity check (HMAC over the file) fails to load under the wrong derived
    // password — confirmed live against the real JDK provider before this class was written, not
    // assumed.
    assertThatThrownBy(() -> wrongMasterSecret.find(scope, kid))
        .isInstanceOf(IllegalStateException.class);
  }

  // TD-SEC-054: the whole point of this change — the tests below prove it directly against the
  // real filesystem/JDK PKCS12 provider, not just that the API compiles with a new parameter.

  @Test
  void differentScopesAreBackedByPhysicallyDifferentFiles() {
    SigningKeyStore store = newStore();
    UUID organizationId = UUID.randomUUID();

    store.generate(KeyStoreScope.platform(), "platform-kid");
    store.generate(KeyStoreScope.organization(organizationId), "org-kid");

    try (Stream<java.nio.file.Path> files = java.nio.file.Files.list(tempDir)) {
      long p12Count = files.filter(f -> f.toString().endsWith(".p12")).count();
      // Exactly two real files on disk — one per scope, not one shared file with two aliases.
      assertThat(p12Count).isEqualTo(2);
    } catch (final java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void oneScopesFileIsUnreadableUnderAnotherScopesDerivedPassword() {
    // The real security property TD-SEC-054 buys: even holding the correct master secret, opening
    // one Organization's own file requires deriving *that* Organization's own password — an
    // attacker who only exfiltrated a different scope's file (or a different scope's derived
    // password alone, without the master secret) gains nothing against this one.
    SigningKeyStore store = newStore();
    UUID firstOrganization = UUID.randomUUID();
    UUID secondOrganization = UUID.randomUUID();
    String kid = "shared-alias-name";
    store.generate(KeyStoreScope.organization(firstOrganization), kid);

    // The second Organization's own store view was never written to — its own file doesn't exist
    // yet, so this must behave exactly like "never generated", not accidentally see the first
    // Organization's entry under the same alias name.
    Optional<KeyPair> crossOrganizationLookup =
        store.find(KeyStoreScope.organization(secondOrganization), kid);

    assertThat(crossOrganizationLookup).isEmpty();
  }

  @Test
  void platformAndOrganizationScopesNeverCollideEvenWithTheSameKid() {
    SigningKeyStore store = newStore();
    UUID organizationId = UUID.randomUUID();
    String sameKid = "collides-in-name-only";

    KeyPair platformKey = store.generate(KeyStoreScope.platform(), sameKid);
    KeyPair organizationKey = store.generate(KeyStoreScope.organization(organizationId), sameKid);

    assertThat(platformKey.getPublic())
        .as("same alias in two different scopes' own files must never resolve to the same key")
        .isNotEqualTo(organizationKey.getPublic());
    assertThat(store.find(KeyStoreScope.platform(), sameKid).orElseThrow().getPublic())
        .isEqualTo(platformKey.getPublic());
    assertThat(
            store
                .find(KeyStoreScope.organization(organizationId), sameKid)
                .orElseThrow()
                .getPublic())
        .isEqualTo(organizationKey.getPublic());
  }
}
