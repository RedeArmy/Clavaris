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
            PlatformScopes.SIGNING_KEYS_PURGE,
            PlatformScopes.PLATFORM_CLIENTS_ROTATE_SECRET,
            PlatformScopes.PLATFORM_CLIENTS_REVOKE,
            PlatformScopes.ACCOUNTS_DELETE,
            PlatformScopes.ORGANIZATIONS_DELETE,
            PlatformScopes.WORKSPACES_WRITE,
            PlatformScopes.WORKSPACE_MEMBERS_WRITE,
            PlatformScopes.WORKSPACE_MEMBERS_REMOVE,
            PlatformScopes.ACCOUNTS_SUSPEND,
            PlatformScopes.SOCIAL_LOGIN_POLICY_WRITE,
            PlatformScopes.WEBHOOK_ENDPOINTS_WRITE,
            PlatformScopes.WEBHOOK_DELIVERIES_REPLAY,
            PlatformScopes.ACCOUNTS_IMPERSONATE,
            PlatformScopes.SOCIAL_CREDENTIALS_WRITE,
            PlatformScopes.SECRET_KEYS_WRITE,
            PlatformScopes.SECRET_KEYS_ROTATE,
            PlatformScopes.ACCOUNT_AUTHENTICATION_POLICY_WRITE,
            PlatformScopes.REDIRECT_POLICY_WRITE,
            PlatformScopes.ACCOUNTS_FORCE_PASSWORD_RESET,
            PlatformScopes.CLIENT_BRANDING_WRITE);
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
