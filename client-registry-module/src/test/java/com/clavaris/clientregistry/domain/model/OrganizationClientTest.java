package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
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

  // SDE-III review, 2026-09-15: the real regression this guards — before this fix, register()
  // validated allowedScopes via PlatformScopes.requireValidScopes alone, which only checks "is
  // this a real, known scope," so a tenant-minted Secret Key could legally hold
  // RATE_LIMIT_POLICY_WRITE/SIGNING_KEYS_ROTATE/SOCIAL_LOGIN_POLICY_WRITE — three scopes this
  // codebase's own PlatformScopes.OPERATOR_ONLY documents as "operator-managed only in v1,"
  // directly contradicting CLAUDE.md §6's locked rate-limit decision.
  @Test
  void registerRejectsEveryOperatorOnlyScope() {
    for (final String operatorOnlyScope : PlatformScopes.OPERATOR_ONLY) {
      assertThatIllegalArgumentException()
          .as("register() must reject the operator-only scope %s", operatorOnlyScope)
          .isThrownBy(
              () ->
                  OrganizationClient.register(
                      UUID.randomUUID(),
                      "sk_test_abc",
                      "argon2id$hashed",
                      List.of(operatorOnlyScope)));
    }
  }

  @Test
  void registerRejectsAnOperatorOnlyScopeEvenAlongsideOtherwiseValidScopes() {
    // Not just "the whole list is operator-only" — one disallowed entry among several allowed
    // ones must still fail closed, not silently drop just that entry.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                OrganizationClient.register(
                    UUID.randomUUID(),
                    "sk_test_abc",
                    "argon2id$hashed",
                    List.of(
                        PlatformScopes.WORKSPACES_WRITE, PlatformScopes.RATE_LIMIT_POLICY_WRITE)));
  }

  // Deliberately asymmetric with registerRejectsEveryOperatorOnlyScope above: reconstitute()
  // rehydrates a row that was already persisted, so it must never reject one an earlier, looser
  // v1 policy allowed to be written — the fix is about closing the mint path, not about making an
  // already-existing row impossible to read back. See OrganizationClient#register's own Javadoc.
  @Test
  void reconstituteStillAcceptsAnOperatorOnlyScopeUnlikeRegister() {
    OrganizationClient rehydrated =
        OrganizationClient.reconstitute(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sk_test_legacy",
            "argon2id$hashed",
            List.of(PlatformScopes.RATE_LIMIT_POLICY_WRITE),
            Instant.now(),
            true);

    assertThat(rehydrated.allowedScopes()).containsExactly(PlatformScopes.RATE_LIMIT_POLICY_WRITE);
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
