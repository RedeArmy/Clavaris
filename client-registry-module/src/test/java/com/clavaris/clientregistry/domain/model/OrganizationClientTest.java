package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationClientTest {

  @Test
  void registerCarriesTheGivenFields() {
    UUID organizationId = UUID.randomUUID();

    OrganizationClient client =
        OrganizationClient.register(
            organizationId,
            "sk_test_abc",
            "argon2id$hashed",
            List.of(PlatformScopes.ACCOUNTS_IMPERSONATE));

    assertThat(client.organizationId()).isEqualTo(organizationId);
    assertThat(client.clientId()).isEqualTo("sk_test_abc");
    assertThat(client.clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(client.allowedScopes()).containsExactly(PlatformScopes.ACCOUNTS_IMPERSONATE);
    assertThat(client.createdAt()).isNotNull();
    assertThat(client.active()).isTrue();
  }

  @Test
  void rejectsABlankClientId() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OrganizationClient.register(UUID.randomUUID(), " ", "argon2id$hashed", List.of()));
  }

  @Test
  void rejectsABlankSecretHash() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> OrganizationClient.register(UUID.randomUUID(), "sk_test_abc", " ", List.of()));
  }

  @Test
  void rejectsAnUnknownScope() {
    // TD-ARCH-004: same vocabulary/rationale as PlatformClientTest's own identical test — this
    // class's own Javadoc already commits to reusing PlatformScopes verbatim, not a parallel
    // namespace.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OrganizationClient.register(
                    UUID.randomUUID(),
                    "sk_test_abc",
                    "argon2id$hashed",
                    List.of("not-a-real-scope")));
  }

  @Test
  void rotateSecretReplacesTheHashKeepingEverythingElse() {
    OrganizationClient original =
        OrganizationClient.register(UUID.randomUUID(), "sk_test_abc", "old-hash", List.of());

    OrganizationClient rotated = original.rotateSecret("new-hash");

    assertThat(rotated.id()).isEqualTo(original.id());
    assertThat(rotated.organizationId()).isEqualTo(original.organizationId());
    assertThat(rotated.clientId()).isEqualTo(original.clientId());
    assertThat(rotated.clientSecretHash()).isEqualTo("new-hash");
    assertThat(rotated.active()).isTrue();
  }

  @Test
  void deactivateFlipsActiveKeepingEverythingElse() {
    OrganizationClient original =
        OrganizationClient.register(UUID.randomUUID(), "sk_test_abc", "hash", List.of());

    OrganizationClient deactivated = original.deactivate();

    assertThat(deactivated.active()).isFalse();
    assertThat(deactivated.id()).isEqualTo(original.id());
    assertThat(deactivated.clientSecretHash()).isEqualTo(original.clientSecretHash());
  }
}
