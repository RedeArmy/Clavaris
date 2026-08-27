package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformClientTest {

  @Test
  void registerCarriesTheGivenFields() {
    PlatformClient client =
        PlatformClient.register(
            "bootstrap-client", "argon2id$hashed", PlatformScopes.BOOTSTRAP_DEFAULT);

    assertThat(client.clientId()).isEqualTo("bootstrap-client");
    assertThat(client.clientSecretHash()).isEqualTo("argon2id$hashed");
    assertThat(client.allowedScopes())
        .containsExactly(
            PlatformScopes.ORGANIZATIONS_WRITE,
            PlatformScopes.RATE_LIMIT_POLICY_WRITE,
            PlatformScopes.SIGNING_KEYS_ROTATE,
            PlatformScopes.PLATFORM_CLIENTS_ROTATE_SECRET,
            PlatformScopes.PLATFORM_CLIENTS_REVOKE,
            PlatformScopes.ACCOUNTS_DELETE,
            PlatformScopes.ORGANIZATIONS_DELETE,
            PlatformScopes.WORKSPACES_WRITE,
            PlatformScopes.WORKSPACE_MEMBERS_WRITE,
            PlatformScopes.WORKSPACE_MEMBERS_REMOVE,
            PlatformScopes.ACCOUNTS_SUSPEND);
    assertThat(client.createdAt()).isNotNull();
  }

  @Test
  void rejectsABlankClientId() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PlatformClient.register(" ", "argon2id$hashed", List.of()));
  }

  @Test
  void rejectsABlankSecretHash_theHighestValueCredentialInTheSystem() {
    // The PlatformClient credential is the single highest-value target in the system — a hasher
    // bug producing an empty hash must fail loudly here, not silently reach
    // persistence as a credential nothing (and everything) would authenticate against.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PlatformClient.register("bootstrap-client", " ", List.of()));
  }
}
