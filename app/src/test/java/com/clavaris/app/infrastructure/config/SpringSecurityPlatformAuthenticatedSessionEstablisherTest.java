package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

class SpringSecurityPlatformAuthenticatedSessionEstablisherTest {

  private final SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
  private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
  private final SpringSecurityPlatformAuthenticatedSessionEstablisher establisher =
      new SpringSecurityPlatformAuthenticatedSessionEstablisher(contextRepository, sessionRegistry);

  @AfterEach
  void clearSecurityContext() {
    // A static holder — every test must leave it as it found it, or later tests in the same JVM
    // silently inherit whichever PlatformAccount the previous test authenticated as.
    SecurityContextHolder.clearContext();
  }

  @Test
  void establishesAnAuthenticatedContextAndRegistersTheNewSessionReturningTheFallbackUrl() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID platformAccountId = UUID.randomUUID();

    String redirectTarget =
        establisher.establish(request, response, platformAccountId, "/platform/dashboard");

    assertThat(redirectTarget).isEqualTo("/platform/dashboard");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo(platformAccountId.toString());
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_PLATFORM_ACCOUNT");
    verify(contextRepository).saveContext(any(), any(), any());
    verify(sessionRegistry)
        .registerNewSession(request.getSession().getId(), platformAccountId.toString());
  }

  @Test
  void changesTheSessionIdWhenARequestAlreadyCarriesOneToPreventSessionFixation() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String preLoginSessionId = request.getSession(true).getId();

    establisher.establish(request, response, UUID.randomUUID(), "/platform/dashboard");

    assertThat(request.getSession(false).getId()).isNotEqualTo(preLoginSessionId);
  }

  @Test
  void resumesTheOriginallyRequestedPageWhenOneWasSaved() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/platform/dashboard");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new HttpSessionRequestCache().saveRequest(request, response);

    String redirectTarget =
        establisher.establish(request, response, UUID.randomUUID(), "/platform/login");

    assertThat(redirectTarget).contains("/platform/dashboard");
  }
}
