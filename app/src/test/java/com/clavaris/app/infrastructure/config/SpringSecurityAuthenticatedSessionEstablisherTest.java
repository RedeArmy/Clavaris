package com.clavaris.app.infrastructure.config;

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
        establisher.establish(request, response, accountId, "/o/x/login?authenticated");

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
            request, response, accountId, SocialProvider.GOOGLE, "/o/x/login?authenticated");

    assertThat(redirectTarget).isEqualTo("/o/x/login?authenticated");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo(accountId.toString());
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ACCOUNT", "FACTOR_AUTHORIZATION_CODE", "AMR_GOOGLE");
    verify(contextRepository).saveContext(any(), any(), any());
  }

  @Test
  void changesTheSessionIdWhenARequestAlreadyCarriesOneToPreventSessionFixation() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String preLoginSessionId = request.getSession(true).getId();

    establisher.establish(request, response, UUID.randomUUID(), "/o/x/login?authenticated");

    assertThat(request.getSession(false).getId()).isNotEqualTo(preLoginSessionId);
  }

  @Test
  void resumesTheOriginallyRequestedPageWhenOneWasSaved() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/o/x/oauth2/authorize");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new HttpSessionRequestCache().saveRequest(request, response);

    String redirectTarget =
        establisher.establish(request, response, UUID.randomUUID(), "/o/x/login?authenticated");

    assertThat(redirectTarget).contains("/o/x/oauth2/authorize");
  }
}
