package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SessionFactorsTest {

  private static final Instant SIGNED_IN = Instant.parse("2026-10-27T10:00:00Z");

  private static UsernamePasswordAuthenticationToken sessionOf(final GrantedAuthority... all) {
    return UsernamePasswordAuthenticationToken.authenticated("acct", null, List.of(all));
  }

  @Test
  void aCapturedSessionComesBackWithTheSameFactorsAndTheOriginalSignInTime() {
    List<String> captured =
        SessionFactors.capture(
            sessionOf(
                FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                    .issuedAt(SIGNED_IN)
                    .build(),
                new SimpleGrantedAuthority("ROLE_ACCOUNT"),
                new SimpleGrantedAuthority("AMR_MFA")));

    List<GrantedAuthority> restored = SessionFactors.restore(captured);

    assertThat(restored)
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("FACTOR_PASSWORD", "ROLE_ACCOUNT", "AMR_MFA");
    FactorGrantedAuthority password =
        restored.stream()
            .filter(FactorGrantedAuthority.class::isInstance)
            .map(FactorGrantedAuthority.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(password.getIssuedAt())
        .as("the authentication time is the original one, not the moment of the restore")
        .isEqualTo(SIGNED_IN);
  }

  @Test
  void onlyTheTierMarkerFactorsAndAmrValuesCanBeCapturedOrRestored() {
    List<String> captured =
        SessionFactors.capture(
            sessionOf(
                new SimpleGrantedAuthority("ROLE_ACCOUNT"),
                new SimpleGrantedAuthority("ROLE_PLATFORM_ACCOUNT"),
                new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertThat(captured).hasSize(1).first().asString().startsWith("ROLE_ACCOUNT|");
    // A tampered attribute cannot add a role the sign-in never gave.
    assertThat(
            SessionFactors.restore(
                List.of("ROLE_ADMIN|0", "ROLE_PLATFORM_ACCOUNT|0", "FACTOR_PASSWORD|1")))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("FACTOR_PASSWORD");
  }

  @Test
  void malformedOrMissingInputRestoresNothingInsteadOfFailing() {
    assertThat(SessionFactors.restore(null)).isEmpty();
    assertThat(SessionFactors.restore(List.of(42, "FACTOR_PASSWORD|not-a-number"))).hasSize(1);
    assertThat(SessionFactors.capture(null)).isEmpty();
  }

  @Test
  void aSignInWithinTheWindowCountsAndAnOlderOneDoesNot() {
    Authentication fresh =
        sessionOf(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now().minusSeconds(60))
                .build());
    Authentication stale =
        sessionOf(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now().minusSeconds(3600))
                .build());

    assertThat(SessionFactors.signedInWithin(fresh, Duration.ofMinutes(10))).isTrue();
    assertThat(SessionFactors.signedInWithin(stale, Duration.ofMinutes(10))).isFalse();
    assertThat(SessionFactors.signedInWithin(null, Duration.ofMinutes(10))).isFalse();
    assertThat(
            SessionFactors.signedInWithin(
                sessionOf(new SimpleGrantedAuthority("ROLE_ACCOUNT")), Duration.ofMinutes(10)))
        .as("no factor, no sign-in time")
        .isFalse();
  }
}
