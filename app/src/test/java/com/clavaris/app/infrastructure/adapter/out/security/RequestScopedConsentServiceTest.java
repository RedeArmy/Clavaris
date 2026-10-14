package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestScopedConsentServiceTest {

  private final OAuth2AuthorizationConsentService delegate =
      mock(OAuth2AuthorizationConsentService.class);
  private final RequestScopedConsentService service = new RequestScopedConsentService(delegate);
  private final OAuth2AuthorizationConsent stored =
      OAuth2AuthorizationConsent.withId("client", "user").scope("profile").build();

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static void currentRequest(final boolean ignoreStoredConsent) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (ignoreStoredConsent) {
      request.setAttribute(
          RequestScopedConsentService.IGNORE_STORED_CONSENT_ATTRIBUTE, Boolean.TRUE);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @Test
  void aMarkedRequestSeesNoStoredConsentButNothingIsDeleted() {
    currentRequest(true);

    assertThat(service.findById("client", "user")).isNull();

    verifyNoInteractions(delegate);
  }

  @Test
  void anUnmarkedRequestReadsTheStoredConsent() {
    when(delegate.findById("client", "user")).thenReturn(stored);
    currentRequest(false);

    assertThat(service.findById("client", "user")).isSameAs(stored);
  }

  @Test
  void outsideAnyRequestTheStoredConsentIsRead() {
    when(delegate.findById("client", "user")).thenReturn(stored);

    assertThat(service.findById("client", "user")).isSameAs(stored);
  }

  @Test
  void savingAndRemovingAlwaysGoToTheStore() {
    currentRequest(true);

    service.save(stored);
    service.remove(stored);

    verify(delegate).save(stored);
    verify(delegate).remove(stored);
  }
}
