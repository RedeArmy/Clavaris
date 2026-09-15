package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
            PlatformScopes.CLIENT_BRANDING_WRITE,
            PlatformScopes.CLIENT_DOMAIN_WRITE);
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

  @Test
  void allowsAnEmptyScopeList() {
    // Existing, intentional state — a revoked-down-to-nothing or not-yet-provisioned client
    // authenticates but can do nothing, not itself a misconfiguration.
    PlatformClient client =
        PlatformClient.register("bootstrap-client", "argon2id$hashed", List.of());

    assertThat(client.allowedScopes()).isEmpty();
  }

  @Test
  void rejectsAnUnknownScope() {
    // TD-ARCH-004: allowedScopes used to be free text nothing validated — a typo'd scope here
    // must fail loudly, not silently grant nothing while looking configured.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PlatformClient.register(
                    "bootstrap-client", "argon2id$hashed", List.of("platform:organzations:write")));
  }

  // SDE-III review, 2026-09-15: version() semantics — see ConcurrentClientModificationException's
  // own Javadoc for the lost-update race the whole field exists to close. The highest-value
  // credential in the system is the one this race matters most for.
  @Test
  void registerStartsAtVersionZero() {
    PlatformClient client =
        PlatformClient.register("bootstrap-client", "argon2id$hashed", List.of());

    assertThat(client.version()).isZero();
  }

  @Test
  void reconstituteKeepsTheRealPersistedVersion() {
    PlatformClient rehydrated =
        PlatformClient.reconstitute(
            UUID.randomUUID(),
            "bootstrap-client",
            "argon2id$hashed",
            List.of(),
            Instant.now(),
            true,
            7);

    assertThat(rehydrated.version()).isEqualTo(7);
  }

  @Test
  void rotateSecretPreservesTheCurrentVersionRatherThanResettingIt() {
    // The in-memory version is never bumped here — the actual increment happens at the DB layer,
    // via the JPA entity's own @Version field, at the next successful save(). This value is only
    // ever "what I was read at," used by the repository's merge() as the expected version to
    // guard the coming UPDATE against a concurrent writer.
    PlatformClient rehydrated =
        PlatformClient.reconstitute(
            UUID.randomUUID(),
            "bootstrap-client",
            "argon2id$hashed",
            List.of(),
            Instant.now(),
            true,
            4);

    PlatformClient rotated = rehydrated.rotateSecret("new-hash");

    assertThat(rotated.version()).isEqualTo(4);
  }

  @Test
  void deactivatePreservesTheCurrentVersionRatherThanResettingIt() {
    PlatformClient rehydrated =
        PlatformClient.reconstitute(
            UUID.randomUUID(),
            "bootstrap-client",
            "argon2id$hashed",
            List.of(),
            Instant.now(),
            true,
            4);

    PlatformClient deactivated = rehydrated.deactivate();

    assertThat(deactivated.version()).isEqualTo(4);
  }
}
