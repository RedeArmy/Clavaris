package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SDE-III review, 2026-09-15 — the real regression this class guards: {@link
 * PlatformScopes#requireValidScopes} alone let an {@code OrganizationClient} (tenant Secret Key) be
 * minted with a scope this same class's own Javadoc already documents as "operator-managed only in
 * v1" ({@link PlatformScopes#RATE_LIMIT_POLICY_WRITE}, CLAUDE.md §6; {@link
 * PlatformScopes#SIGNING_KEYS_ROTATE}; {@link PlatformScopes#SOCIAL_LOGIN_POLICY_WRITE}, ADR-0020
 * Decision 3) — a live contradiction of a locked product decision. See {@link
 * OrganizationClientTest#registerRejectsEveryOperatorOnlyScope} for the same invariant proved at
 * the actual call site.
 */
class PlatformScopesTest {

  @Test
  void requireValidScopesAcceptsEveryBootstrapDefaultScope() {
    assertThat(PlatformScopes.requireValidScopes(PlatformScopes.BOOTSTRAP_DEFAULT))
        .isEqualTo(PlatformScopes.BOOTSTRAP_DEFAULT);
  }

  @Test
  void requireValidScopesRejectsAnUnknownScope() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PlatformScopes.requireValidScopes(List.of("not-a-real-scope")));
  }

  @Test
  void requireValidScopesForOrganizationClientAcceptsEveryScopeInTheAllowedList() {
    // Every entry in ORGANIZATION_CLIENT_ALLOWED must, by construction, pass the narrower
    // validator — this is the list the dashboard's own create form offers.
    assertThat(
            PlatformScopes.requireValidScopesForOrganizationClient(
                PlatformScopes.ORGANIZATION_CLIENT_ALLOWED))
        .isEqualTo(PlatformScopes.ORGANIZATION_CLIENT_ALLOWED);
  }

  @Test
  void requireValidScopesForOrganizationClientRejectsEveryOperatorOnlyScope() {
    for (final String operatorOnlyScope : PlatformScopes.OPERATOR_ONLY) {
      assertThatIllegalArgumentException()
          .as("the narrower validator must reject %s", operatorOnlyScope)
          .isThrownBy(
              () ->
                  PlatformScopes.requireValidScopesForOrganizationClient(
                      List.of(operatorOnlyScope)));
    }
  }

  @Test
  void requireValidScopesForOrganizationClientStillRejectsAnUnknownScope() {
    // The narrower validator must delegate to, not bypass, requireValidScopes's own check.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PlatformScopes.requireValidScopesForOrganizationClient(
                    List.of("not-a-real-scope")));
  }

  @Test
  void organizationClientAllowedIsExactlyBootstrapDefaultMinusOperatorOnly() {
    for (final String scope : PlatformScopes.BOOTSTRAP_DEFAULT) {
      final boolean isOperatorOnly = PlatformScopes.OPERATOR_ONLY.contains(scope);
      assertThat(PlatformScopes.ORGANIZATION_CLIENT_ALLOWED.contains(scope))
          .as("ORGANIZATION_CLIENT_ALLOWED membership for %s", scope)
          .isEqualTo(!isOperatorOnly);
    }
    assertThat(PlatformScopes.BOOTSTRAP_DEFAULT.size() - PlatformScopes.OPERATOR_ONLY.size())
        .isEqualTo(PlatformScopes.ORGANIZATION_CLIENT_ALLOWED.size());
  }

  @Test
  void operatorOnlyScopesAreDrawnFromBootstrapDefaultNotAParallelVocabulary() {
    // Guards against a future typo introducing an OPERATOR_ONLY entry that isn't itself a real
    // scope — would otherwise be a silent no-op exclusion (never actually mintable anyway) instead
    // of a caught mistake.
    assertThat(PlatformScopes.BOOTSTRAP_DEFAULT).containsAll(PlatformScopes.OPERATOR_ONLY);
  }
}
