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
    // Security finding, 2026-10-07: was a hand-enumerated literal list, which broke the moment
    // PlatformScopes gained a new entry (TD-FUT-041's own ACCOUNTS_PROFILE_WRITE) despite this
    // test's own real intent being "allowedScopes carries back whatever was passed to register,"
    // not "BOOTSTRAP_DEFAULT has exactly these N entries." Comparing against the constant actually
    // passed in above (line 17) tests that real invariant without needing to stay in sync by hand
    // every time a new scope is added.
    assertThat(client.allowedScopes()).containsExactlyElementsOf(PlatformScopes.BOOTSTRAP_DEFAULT);
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
