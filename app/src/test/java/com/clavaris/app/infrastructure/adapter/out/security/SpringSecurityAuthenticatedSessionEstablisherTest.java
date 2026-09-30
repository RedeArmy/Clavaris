package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.domain.model.SocialProvider;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * TD-FUT-008 (closed): no dedicated test existed for this class before ADR-0020's own refactor
 * ({@code establish()} split into a shared {@code establishWithAuthorities} helper plus the new
 * {@code establishViaSocialLogin}) — this covers both entry points against the one shared
 * implementation, same coverage shape as {@code
 * SpringSecurityPlatformAuthenticatedSessionEstablisherTest}.
 */
class SpringSecurityAuthenticatedSessionEstablisherTest {

  private final SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
  private final SpringSecurityAuthenticatedSessionEstablisher establisher =
      new SpringSecurityAuthenticatedSessionEstablisher(contextRepository);

  @AfterEach
  void clearSecurityContext() {
    // A static holder — every test must leave it as it found it, or later tests in the same JVM
    // silently inherit whichever Account the previous test authenticated as.
    SecurityContextHolder.clearContext();
  }

  @Test
  void establishSetsThePasswordAuthorityAndNoAmrMarker() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID accountId = UUID.randomUUID();

    String redirectTarget =
        establisher.establish(request, response, accountId, false, "/o/x/login?authenticated");

    assertThat(redirectTarget).isEqualTo("/o/x/login?authenticated");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo(accountId.toString());
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ACCOUNT", "FACTOR_PASSWORD")
        .noneMatch(authority -> authority.startsWith("AMR_"));
    verify(contextRepository).saveContext(any(), any(), any());
  }

  @Test
  void establishViaSocialLoginSetsTheAuthorizationCodeAuthorityAndTheProviderAmrMarker() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID accountId = UUID.randomUUID();

    String redirectTarget =
        establisher.establishViaSocialLogin(
            request, response, accountId, SocialProvider.GOOGLE, false, "/o/x/login?authenticated");

    assertThat(redirectTarget).isEqualTo("/o/x/login?authenticated");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo(accountId.toString());
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ACCOUNT", "FACTOR_AUTHORIZATION_CODE", "AMR_GOOGLE")
        .noneMatch(authority -> authority.equals("AMR_MFA"));
    verify(contextRepository).saveContext(any(), any(), any());
  }

  // TD-SEC-048: proves the actual fix - a Device Trust step-up composes RFC 8176's "mfa" value
  // alongside the primary factor's own AMR value, exercised across both establish() and
  // establishViaSocialLogin() (not just one), since AuthenticationContextClaimsCustomizerTest
  // already covers resolveAmr's own composition logic in isolation - this proves the establisher
  // side actually feeds it a second authority in the first place.
  @Test
  void establishComposesTheMfaAmrMarkerOnADeviceTrustStepUp() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID accountId = UUID.randomUUID();

    establisher.establish(request, response, accountId, true, "/o/x/login?authenticated");

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ACCOUNT", "FACTOR_PASSWORD", "AMR_MFA");
  }

  @Test
  void establishViaSocialLoginComposesTheMfaAmrMarkerOnADeviceTrustStepUp() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID accountId = UUID.randomUUID();

    establisher.establishViaSocialLogin(
        request, response, accountId, SocialProvider.GOOGLE, true, "/o/x/login?authenticated");

    // TD-SEC-048: composes ["google","mfa"], deliberately not a second AMR_GOOGLE - same "no
    // ambiguous duplicate value" reasoning as the ONE_TIME_EMAIL_PROOF case (see
    // AuthenticatedSessionEstablisher's own Javadoc), extended here to a third originally-named
    // primary factor the tech-debt register's own original text never considered.
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ACCOUNT", "FACTOR_AUTHORIZATION_CODE", "AMR_GOOGLE", "AMR_MFA");
  }

  @Test
  void changesTheSessionIdWhenARequestAlreadyCarriesOneToPreventSessionFixation() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String preLoginSessionId = request.getSession(true).getId();

    establisher.establish(request, response, UUID.randomUUID(), false, "/o/x/login?authenticated");

    assertThat(request.getSession(false).getId()).isNotEqualTo(preLoginSessionId);
  }

  @Test
  void resumesTheOriginallyRequestedPageWhenOneWasSaved() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/o/x/oauth2/authorize");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new HttpSessionRequestCache().saveRequest(request, response);

    String redirectTarget =
        establisher.establish(
            request, response, UUID.randomUUID(), false, "/o/x/login?authenticated");

    assertThat(redirectTarget).contains("/o/x/oauth2/authorize");
  }
}
