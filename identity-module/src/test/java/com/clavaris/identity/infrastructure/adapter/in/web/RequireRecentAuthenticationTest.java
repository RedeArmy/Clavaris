package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicySnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RequireRecentAuthenticationTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final int WINDOW_MINUTES = 10;

  private SessionPolicyProvider sessionPolicyProvider;
  private RequireRecentAuthentication requireRecentAuthentication;

  @BeforeEach
  void setUp() {
    sessionPolicyProvider = mock(SessionPolicyProvider.class);
    when(sessionPolicyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(new SessionPolicySnapshot(10_080, 10_080, WINDOW_MINUTES, true));
    requireRecentAuthentication = new RequireRecentAuthentication(sessionPolicyProvider);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void isNotStaleWhenAuthenticatedWellWithinTheReverificationWindow() {
    authenticateAt(Instant.now().minusSeconds(30));

    assertThat(requireRecentAuthentication.isStale(ORGANIZATION_ID)).isFalse();
  }

  @Test
  void isStaleWhenAuthenticatedBeforeTheReverificationWindow() {
    authenticateAt(Instant.now().minus(WINDOW_MINUTES + 1, java.time.temporal.ChronoUnit.MINUTES));

    assertThat(requireRecentAuthentication.isStale(ORGANIZATION_ID)).isTrue();
  }

  // Fail-closed: no Authentication at all in the security context.
  @Test
  void isStaleWhenThereIsNoAuthenticationAtAll() {
    SecurityContextHolder.clearContext();

    assertThat(requireRecentAuthentication.isStale(ORGANIZATION_ID)).isTrue();
  }

  // Fail-closed: authenticated, but carrying no FactorGrantedAuthority at all — nothing here to
  // prove a recent login with.
  @Test
  void isStaleWhenTheAuthenticationCarriesNoFactorGrantedAuthority() {
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"));
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated("some-account-id", null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThat(requireRecentAuthentication.isStale(ORGANIZATION_ID)).isTrue();
  }

  @Test
  void usesTheMostRecentFactorWhenMoreThanOneIsPresent() {
    List<GrantedAuthority> authorities =
        List.of(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(
                    Instant.now().minus(WINDOW_MINUTES + 5, java.time.temporal.ChronoUnit.MINUTES))
                .build(),
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.OTT_AUTHORITY)
                .issuedAt(Instant.now().minusSeconds(10))
                .build());
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated("some-account-id", null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThat(requireRecentAuthentication.isStale(ORGANIZATION_ID)).isFalse();
  }

  private static void authenticateAt(final Instant issuedAt) {
    List<GrantedAuthority> authorities =
        List.of(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(issuedAt)
                .build());
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated("some-account-id", null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
