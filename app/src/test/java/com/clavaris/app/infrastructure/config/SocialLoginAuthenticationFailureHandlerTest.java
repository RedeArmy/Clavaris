package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class SocialLoginAuthenticationFailureHandlerTest {

  private final SocialLoginAuthenticationFailureHandler handler =
      new SocialLoginAuthenticationFailureHandler();

  @Test
  void redirectsToThePlatformLoginErrorWhenNoOrganizationSessionAttributeExists() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(
        request, response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

    assertThat(response.getRedirectedUrl()).isEqualTo("/platform/login?socialLoginError");
  }

  @Test
  void redirectsToTheTenantLoginErrorAndClearsTheSessionAttributeWhenOnePresent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    String organizationId = "11111111-1111-1111-1111-111111111111";
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE, organizationId);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(
        request, response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/login?socialLoginError");
    assertThat(
            request
                .getSession(false)
                .getAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE))
        .isNull();
  }
}
