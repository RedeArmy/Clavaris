package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// TD-SEC-102: the whole-file removal and the listing the orphan sweep is built on.
class OrganizationKeyFilesTest {

  @TempDir private Path tempDir;

  private SigningKeyStore newStore() {
    return new SigningKeyStore(
        tempDir.resolve("signing-keys.p12").toString(), "a-test-key-store-password");
  }

  private void makeOld(final KeyStoreScope scope) throws IOException {
    Files.setLastModifiedTime(
        tempDir.resolve("signing-keys-" + scope.id() + ".p12"),
        FileTime.from(Instant.now().minus(Duration.ofDays(2))));
  }

  @Test
  void deleteRemovesTheWholeFileWithoutKnowingItsKids() {
    SigningKeyStore store = newStore();
    UUID organizationId = UUID.randomUUID();
    KeyStoreScope scope = KeyStoreScope.organization(organizationId);
    String kid = UUID.randomUUID().toString();
    store.generate(scope, kid);
    store.generate(scope, UUID.randomUUID().toString());

    assertThat(OrganizationKeyFiles.delete(store.baseDirectory(), organizationId)).isTrue();

    assertThat(store.find(scope, kid)).isEmpty();
    assertThat(OrganizationKeyFiles.delete(store.baseDirectory(), organizationId))
        .as("nothing left to delete")
        .isFalse();
  }

  @Test
  void deleteLeavesOtherScopesAlone() {
    SigningKeyStore store = newStore();
    UUID gone = UUID.randomUUID();
    KeyStoreScope kept = KeyStoreScope.organization(UUID.randomUUID());
    store.generate(KeyStoreScope.organization(gone), UUID.randomUUID().toString());
    String keptKid = UUID.randomUUID().toString();
    store.generate(kept, keptKid);

    OrganizationKeyFiles.delete(store.baseDirectory(), gone);

    assertThat(store.find(kept, keptKid)).isPresent();
  }

  @Test
  void listsOnlyOrganizationFilesOlderThanTheGivenAge() throws Exception {
    SigningKeyStore store = newStore();
    UUID oldOrganization = UUID.randomUUID();
    UUID freshOrganization = UUID.randomUUID();
    store.generate(KeyStoreScope.organization(oldOrganization), UUID.randomUUID().toString());
    store.generate(KeyStoreScope.organization(freshOrganization), UUID.randomUUID().toString());
    makeOld(KeyStoreScope.organization(oldOrganization));

    assertThat(OrganizationKeyFiles.idsOlderThan(store.baseDirectory(), Duration.ofHours(1)))
        .containsExactly(oldOrganization);
  }

  @Test
  void theListingIgnoresThePlatformFileAndFilesThatAreNotNamedAfterAnOrganization()
      throws Exception {
    SigningKeyStore store = newStore();
    store.generate(KeyStoreScope.platform(), UUID.randomUUID().toString());
    makeOld(KeyStoreScope.platform());
    Path stray = tempDir.resolve("signing-keys-org-not-a-uuid.p12");
    Files.writeString(stray, "x");
    Files.setLastModifiedTime(stray, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

    assertThat(OrganizationKeyFiles.idsOlderThan(store.baseDirectory(), Duration.ofHours(1)))
        .isEmpty();
  }
}
