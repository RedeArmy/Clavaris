package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// TD-SEC-009: proves the writer's own branching logic in isolation — which policy, on which
// content type, on which path — independent of whether Spring Security's own HeaderWriterFilter
// timing actually calls this after setContentType() in a real request (verified separately,
// live, against a real running instance — see this class's own Javadoc for why that's a real
// question, not an assumption).
class ContentSecurityPolicyHeaderWriterTest {

  private static final String HEADER_NAME = "Content-Security-Policy";
  private static final String ORG_REGISTER_PATH =
      "/o/11111111-1111-1111-1111-111111111111/register";
  private static final String ORG_LOGIN_PATH = "/o/11111111-1111-1111-1111-111111111111/login";
  private static final String ORG_LOGIN_SOCIAL_PATH =
      "/o/11111111-1111-1111-1111-111111111111/login/social/google";
  private static final String ORG_CONSENT_PATH =
      "/o/11111111-1111-1111-1111-111111111111/oauth2/authorize";

  private final ContentSecurityPolicyHeaderWriter writer = new ContentSecurityPolicyHeaderWriter();

  @Test
  void setsTheStrictPolicyOnAnHtmlResponseForAnOrdinaryHostedUiPath() {
    HttpServletRequest request = requestWithUri(ORG_REGISTER_PATH);
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    writer.writeHeaders(request, response);

    verify(response)
        .setHeader(
            HEADER_NAME,
            "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; "
                + "font-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
                + "form-action 'self'; frame-ancestors 'none'");
  }

  // Code review finding (2026-09-01): identity/login.html now loads its own real, same-origin
  // script (login-submit-guard.js) — see ContentSecurityPolicyHeaderWriter's own Javadoc for why
  // this earns its own policy, distinct from both the strict default and the consent page's.
  @Test
  void setsTheLoginPagePolicyOnlyForTheLoginPagePathItself() {
    HttpServletRequest request = requestWithUri(ORG_LOGIN_PATH);
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    writer.writeHeaders(request, response);

    verify(response)
        .setHeader(
            HEADER_NAME,
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                + "font-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
                + "form-action 'self'; frame-ancestors 'none'");
  }

  // A sibling path under the same /o/{organizationId}/login/** prefix — the plain "sign in with
  // Google" link, not the password form — must not be swept into the login page's own carve-out;
  // it has no script of its own and should stay on the strict default.
  @Test
  void doesNotWidenThePolicyForTheSocialLoginLinkPath() {
    HttpServletRequest request = requestWithUri(ORG_LOGIN_SOCIAL_PATH);
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    writer.writeHeaders(request, response);

    verify(response)
        .setHeader(
            HEADER_NAME,
            "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; "
                + "font-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
                + "form-action 'self'; frame-ancestors 'none'");
  }

  @Test
  void setsTheRelaxedPolicyOnlyForSasOwnConsentPagePath() {
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    writer.writeHeaders(request, response);

    verify(response)
        .setHeader(
            HEADER_NAME,
            "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                + "style-src 'self' https://stackpath.bootstrapcdn.com; img-src 'self'; "
                + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
                + "form-action 'self'; frame-ancestors 'none'");
  }

  @Test
  void neverSetsTheHeaderOnANonHtmlResponse() {
    HttpServletRequest request = requestWithUri(ORG_LOGIN_PATH);
    HttpServletResponse response = responseWithContentType("application/json");

    writer.writeHeaders(request, response);

    verify(response, never()).setHeader(eq(HEADER_NAME), anyString());
  }

  @Test
  void neverSetsTheHeaderWhenContentTypeIsUnset() {
    HttpServletRequest request = requestWithUri(ORG_LOGIN_PATH);
    HttpServletResponse response = responseWithContentType(null);

    writer.writeHeaders(request, response);

    verify(response, never()).setHeader(eq(HEADER_NAME), anyString());
  }

  @Test
  void neverOverridesAHeaderAlreadySetByAnEarlierWriter() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.containsHeader(HEADER_NAME)).thenReturn(true);

    writer.writeHeaders(request, response);

    verify(response, never()).setHeader(eq(HEADER_NAME), anyString());
    // Confirms the early-return path doesn't even bother reading the content type/URI first.
    verify(response, never()).getContentType();
  }

  @Test
  void aConsentPathThatIsNotHtmlNeverGetsAnyRelaxedPolicy() {
    // Defensive: content type is checked before the path match, so a non-HTML response at the
    // consent path (not expected in practice, but not this class's job to assume away) never
    // picks up the relaxed policy just because the URL matches.
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
    HttpServletResponse response = responseWithContentType("application/json");

    writer.writeHeaders(request, response);

    verify(response, never()).setHeader(eq(HEADER_NAME), anyString());
  }

  // Sanity: all three policies are genuinely different strings, or the "three policies" design
  // claim this class's own Javadoc makes would be silently false.
  @Test
  void theThreePoliciesAreActuallyDifferent() {
    HttpServletRequest strictRequest = requestWithUri(ORG_REGISTER_PATH);
    HttpServletResponse strictResponse = responseWithContentType("text/html");
    HttpServletRequest loginRequest = requestWithUri(ORG_LOGIN_PATH);
    HttpServletResponse loginResponse = responseWithContentType("text/html");
    HttpServletRequest consentRequest = requestWithUri(ORG_CONSENT_PATH);
    HttpServletResponse consentResponse = responseWithContentType("text/html");

    writer.writeHeaders(strictRequest, strictResponse);
    writer.writeHeaders(loginRequest, loginResponse);
    writer.writeHeaders(consentRequest, consentResponse);

    ArgumentCaptor<String> strictCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> loginCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> consentCaptor = ArgumentCaptor.forClass(String.class);
    verify(strictResponse).setHeader(eq(HEADER_NAME), strictCaptor.capture());
    verify(loginResponse).setHeader(eq(HEADER_NAME), loginCaptor.capture());
    verify(consentResponse).setHeader(eq(HEADER_NAME), consentCaptor.capture());

    assertThat(strictCaptor.getValue()).isNotEqualTo(loginCaptor.getValue());
    assertThat(strictCaptor.getValue()).isNotEqualTo(consentCaptor.getValue());
    assertThat(loginCaptor.getValue()).isNotEqualTo(consentCaptor.getValue());

    assertThat(strictCaptor.getValue()).doesNotContain("unsafe-inline");
    // The login page gets a real script-src, but deliberately never 'unsafe-inline' — unlike the
    // consent page, this one's script is project-owned and has no reason to be inline.
    assertThat(loginCaptor.getValue()).contains("script-src 'self'");
    assertThat(loginCaptor.getValue()).doesNotContain("unsafe-inline");
    assertThat(consentCaptor.getValue()).contains("unsafe-inline");
  }

  private static HttpServletRequest requestWithUri(final String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(uri);
    return request;
  }

  private static HttpServletResponse responseWithContentType(final String contentType) {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getContentType()).thenReturn(contentType);
    return response;
  }
}
