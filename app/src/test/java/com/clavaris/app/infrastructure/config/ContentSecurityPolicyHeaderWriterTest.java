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
  // TD-SEC-011: flat, org-agnostic — see ContentSecurityPolicyHeaderWriter's own Javadoc for why
  // this is no longer "/o/{organizationId}/oauth2/authorize".
  private static final String ORG_CONSENT_PATH = "/oauth2/consent";

  private final ContentSecurityPolicyHeaderWriter writer =
      new ContentSecurityPolicyHeaderWriter(mock(EmbeddingEligibilityChecker.class));

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

  // TD-SEC-011: SAS's own DefaultConsentPage can no longer render at all (consentPage(...) is now
  // unconditionally configured) — the project-owned replacement needs no script/CDN, so this path
  // gets the plain strict policy, same as any other ordinary hosted-UI path.
  @Test
  void setsTheStrictPolicyForTheProjectOwnedConsentPagePath() {
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
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

  // TD-SEC-011: the consent page now shares STRICT_POLICY's own text exactly (no script/CDN needed
  // any more) — only the login page's own real script-src earns a distinct base policy. Renamed
  // from "theThreePoliciesAreActuallyDifferent": that claim is no longer true by design, not a
  // regression — see ContentSecurityPolicyHeaderWriter's own Javadoc.
  @Test
  void theConsentPageSharesTheStrictPolicyButTheLoginPageDoesNot() {
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

    assertThat(strictCaptor.getValue()).isEqualTo(consentCaptor.getValue());
    assertThat(strictCaptor.getValue()).isNotEqualTo(loginCaptor.getValue());

    assertThat(strictCaptor.getValue()).doesNotContain("unsafe-inline");
    // The login page gets a real script-src, but deliberately never 'unsafe-inline'.
    assertThat(loginCaptor.getValue()).contains("script-src 'self'");
    assertThat(loginCaptor.getValue()).doesNotContain("unsafe-inline");
    assertThat(consentCaptor.getValue()).doesNotContain("unsafe-inline");
  }

  // ADR-0009 §1/§4: display=modal + an embedding-eligible clientId relaxes frame-ancestors on the
  // login page — only there, only for that one request, only when the checker actually resolves an
  // origin.
  @Test
  void relaxesFrameAncestorsOnTheLoginPageWhenDisplayModalAndClientIdAreEligible() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    when(checker.resolveAllowedFrameAncestor("jobseeker-web"))
        .thenReturn(java.util.Optional.of("https://jobseeker.example.com"));
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_LOGIN_PATH);
    when(request.getParameter("display")).thenReturn("modal");
    when(request.getParameter("clientId")).thenReturn("jobseeker-web");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME),
            org.mockito.ArgumentMatchers.contains("frame-ancestors https://jobseeker.example.com"));
  }

  @Test
  void keepsFrameAncestorsNoneWhenDisplayModalButTheCheckerFindsNoEligibleOrigin() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    when(checker.resolveAllowedFrameAncestor("unverified-client"))
        .thenReturn(java.util.Optional.empty());
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_LOGIN_PATH);
    when(request.getParameter("display")).thenReturn("modal");
    when(request.getParameter("clientId")).thenReturn("unverified-client");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME), org.mockito.ArgumentMatchers.contains("frame-ancestors 'none'"));
  }

  @Test
  void neverRelaxesFrameAncestorsWithoutDisplayModalEvenForAnEligibleClient() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    when(checker.resolveAllowedFrameAncestor(org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.Optional.of("https://jobseeker.example.com"));
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_LOGIN_PATH);
    when(request.getParameter("clientId")).thenReturn("jobseeker-web");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME), org.mockito.ArgumentMatchers.contains("frame-ancestors 'none'"));
    verify(checker, never()).resolveAllowedFrameAncestor(org.mockito.ArgumentMatchers.any());
  }

  // TD-SEC-011: same display=modal gate as the login page, deliberately kept even though SAS's
  // own internal redirect to this page never actually forwards that param in practice (see
  // ContentSecurityPolicyHeaderWriter's own Javadoc — a separately tracked follow-up, not fixed
  // here). Deliberately reads "client_id" (OAuth2's own parameter name), never this project's own
  // "clientId" convention.
  @Test
  void relaxesFrameAncestorsOnTheConsentPageWhenDisplayModalAndClientIdAreEligible() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    when(checker.resolveAllowedFrameAncestor("jobseeker-web"))
        .thenReturn(java.util.Optional.of("https://jobseeker.example.com"));
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
    when(request.getParameter("display")).thenReturn("modal");
    when(request.getParameter("client_id")).thenReturn("jobseeker-web");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME),
            org.mockito.ArgumentMatchers.contains("frame-ancestors https://jobseeker.example.com"));
  }

  @Test
  void keepsFrameAncestorsNoneOnTheConsentPageWhenDisplayModalButTheCheckerFindsNoEligibleOrigin() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    when(checker.resolveAllowedFrameAncestor("unverified-client"))
        .thenReturn(java.util.Optional.empty());
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
    when(request.getParameter("display")).thenReturn("modal");
    when(request.getParameter("client_id")).thenReturn("unverified-client");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME), org.mockito.ArgumentMatchers.contains("frame-ancestors 'none'"));
  }

  // TD-SEC-011: exactly the shape a real, non-embedded consent render has today (no display param
  // at all, e.g. a development-tier Organization's ordinary sign-in) — live-caught via
  // AuthorizationCodeFlowIntegrationTest before this gate was reinstated: dropping it relaxed
  // frame-ancestors to the development wildcard for every such request, not just genuinely
  // embedded ones.
  @Test
  void neverRelaxesFrameAncestorsOnTheConsentPageWithoutDisplayModalEvenForAnEligibleClient() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    when(checker.resolveAllowedFrameAncestor(org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.Optional.of("https://jobseeker.example.com"));
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
    when(request.getParameter("client_id")).thenReturn("jobseeker-web");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME), org.mockito.ArgumentMatchers.contains("frame-ancestors 'none'"));
    verify(checker, never()).resolveAllowedFrameAncestor(org.mockito.ArgumentMatchers.any());
  }

  // The consent page's own camelCase "clientId" (this project's login-page-only convention) must
  // never be read here — only OAuth2's own "client_id" — or the relaxation would silently key off
  // the wrong parameter for every real consent request.
  @Test
  void neverReadsTheLoginPagesOwnCamelCaseClientIdParamOnTheConsentPage() {
    EmbeddingEligibilityChecker checker = mock(EmbeddingEligibilityChecker.class);
    // Stubbed only for the literal "jobseeker-web" — if the writer wrongly called this with
    // null (the unstubbed request.getParameter("client_id") below), Mockito's own default
    // Optional.empty() for an unstubbed argument is exactly what proves the bug this test guards
    // against would otherwise go undetected.
    when(checker.resolveAllowedFrameAncestor("jobseeker-web"))
        .thenReturn(java.util.Optional.of("https://jobseeker.example.com"));
    ContentSecurityPolicyHeaderWriter modalAwareWriter =
        new ContentSecurityPolicyHeaderWriter(checker);
    HttpServletRequest request = requestWithUri(ORG_CONSENT_PATH);
    when(request.getParameter("display")).thenReturn("modal");
    when(request.getParameter("clientId")).thenReturn("jobseeker-web");
    HttpServletResponse response = responseWithContentType("text/html;charset=UTF-8");

    modalAwareWriter.writeHeaders(request, response);

    verify(response)
        .setHeader(
            eq(HEADER_NAME), org.mockito.ArgumentMatchers.contains("frame-ancestors 'none'"));
    verify(checker).resolveAllowedFrameAncestor(null);
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
