package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OrganizationLoginRedirectEntryPointTest {

  private final OrganizationLoginRedirectEntryPoint entryPoint =
      new OrganizationLoginRedirectEntryPoint();
  private static final String ORGANIZATION_ID = "11111111-1111-1111-1111-111111111111";

  @Test
  void redirectsToTheHostedLoginPageForTheSameOrganization() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + ORGANIZATION_ID + "/oauth2/authorize");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, anAuthenticationException());

    assertThat(response.getRedirectedUrl()).isEqualTo("/o/" + ORGANIZATION_ID + "/login");
  }

  // ADR-0009 §1: display=modal (and client_id) must survive this redirect for the login page to
  // resolve embedding eligibility — this is the regression check for that forwarding.
  @Test
  void forwardsClientIdAndDisplayOntoTheLoginRedirect() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + ORGANIZATION_ID + "/oauth2/authorize");
    request.setParameter("client_id", "jobseeker-web");
    request.setParameter("display", "modal");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, anAuthenticationException());

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + ORGANIZATION_ID + "/login?clientId=jobseeker-web&display=modal");
  }

  @Test
  void forwardsOnlyClientIdWhenDisplayIsAbsent() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + ORGANIZATION_ID + "/oauth2/authorize");
    request.setParameter("client_id", "jobseeker-web");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, anAuthenticationException());

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + ORGANIZATION_ID + "/login?clientId=jobseeker-web");
  }

  // TD-FUT-045 (security finding, 2026-10-08): the account/profile page's own embedded-iframe
  // access never goes through /oauth2/authorize, so it carries clientId (camelCase) directly,
  // never the OAuth2 spec's client_id — this is the regression check for that fallback. Confirmed
  // live: before this fix, an unauthenticated request here dropped clientId entirely, the login
  // redirect landed without it, and ContentSecurityPolicyHeaderWriter's own fail-closed posture
  // then correctly refused to relax frame-ancestors for a clientId it never received.
  @Test
  void fallsBackToTheCamelCaseClientIdParamWhenTheOAuth2OneIsAbsent() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + ORGANIZATION_ID + "/account/profile");
    request.setParameter("clientId", "jobseeker-web");
    request.setParameter("display", "modal");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, anAuthenticationException());

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + ORGANIZATION_ID + "/login?clientId=jobseeker-web&display=modal");
  }

  // The OAuth2 spec's own client_id must still win when both are somehow present — same
  // precedence ContentSecurityPolicyHeaderWriter's consent-page handling already establishes.
  @Test
  void prefersTheOAuth2ClientIdParamWhenBothAreSomehowPresent() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/o/" + ORGANIZATION_ID + "/oauth2/authorize");
    request.setParameter("client_id", "oauth2-shaped");
    request.setParameter("clientId", "camel-case-shaped");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, anAuthenticationException());

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + ORGANIZATION_ID + "/login?clientId=oauth2-shaped");
  }

  @Test
  void returns401WhenTheRequestPathHasNoOrganizationSegment() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(request, response, anAuthenticationException());

    assertThat(response.getStatus()).isEqualTo(401);
  }

  private static OAuth2AuthenticationException anAuthenticationException() {
    return new OAuth2AuthenticationException(new OAuth2Error("access_denied"));
  }
}
