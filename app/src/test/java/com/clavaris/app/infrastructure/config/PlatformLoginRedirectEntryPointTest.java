package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class PlatformLoginRedirectEntryPointTest {

  private final PlatformLoginRedirectEntryPoint entryPoint = new PlatformLoginRedirectEntryPoint();

  @Test
  void redirectsAnUnauthenticatedRequestToThePlatformLoginPage() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, new BadCredentialsException("no session"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/platform/login");
  }

  @Test
  void prependsTheContextPathWhenTheAppIsNotDeployedAtRoot() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContextPath("/clavaris");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, new BadCredentialsException("no session"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/clavaris/platform/login");
  }
}
