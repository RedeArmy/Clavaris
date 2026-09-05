package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderResult;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderUseCase;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderResult;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderUseCase;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialLoginNotAllowedException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.infrastructure.adapter.in.web.AuthenticatedSessionEstablisher;
import com.clavaris.identity.infrastructure.adapter.in.web.PlatformAuthenticatedSessionEstablisher;
import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class SocialLoginAuthenticationSuccessHandlerTest {

  private AuthenticateWithSocialProviderUseCase tenantUseCase;
  private AuthenticatePlatformAccountWithSocialProviderUseCase platformUseCase;
  private AuthenticatedSessionEstablisher tenantSessions;
  private PlatformAuthenticatedSessionEstablisher platformSessions;
  private RecordAccountLoginDeviceUseCase recordLoginDevice;
  private RedirectUrlResolver redirectUrlResolver;
  private SocialLoginAuthenticationSuccessHandler handler;

  @BeforeEach
  void setUp() {
    tenantUseCase = mock(AuthenticateWithSocialProviderUseCase.class);
    platformUseCase = mock(AuthenticatePlatformAccountWithSocialProviderUseCase.class);
    tenantSessions = mock(AuthenticatedSessionEstablisher.class);
    platformSessions = mock(PlatformAuthenticatedSessionEstablisher.class);
    recordLoginDevice = mock(RecordAccountLoginDeviceUseCase.class);
    redirectUrlResolver = mock(RedirectUrlResolver.class);
    when(redirectUrlResolver.resolve(any(), any(), any(), any()))
        .thenReturn(java.util.Optional.empty());
    handler =
        new SocialLoginAuthenticationSuccessHandler(
            tenantUseCase,
            platformUseCase,
            tenantSessions,
            platformSessions,
            recordLoginDevice,
            redirectUrlResolver);
  }

  private OAuth2AuthenticationToken googleToken(final String email, final boolean verified) {
    OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token-value")
            .subject("google-sub-123")
            .claim("email", email)
            .claim("email_verified", verified)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    DefaultOidcUser principal =
        new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
  }

  private OAuth2AuthenticationToken gitHubToken(final String verifiedEmail) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("id", 987654);
    if (verifiedEmail != null) {
      attributes.put(GitHubVerifiedEmailUserService.VERIFIED_EMAIL_ATTRIBUTE, verifiedEmail);
    }
    DefaultOAuth2User principal =
        new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
  }

  @Test
  void tenantLoginEstablishesASocialSessionAndRedirectsToItsTarget() throws Exception {
    UUID organizationId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    AccountId accountId = AccountId.newId();
    when(tenantUseCase.handle(any()))
        .thenReturn(new AuthenticateWithSocialProviderResult.LoggedIn(accountId));
    when(tenantSessions.establishViaSocialLogin(any(), any(), eq(accountId.value()), any(), any()))
        .thenReturn("/o/" + organizationId + "/oauth2/authorize?client_id=abc");

    handler.onAuthenticationSuccess(request, response, googleToken("user@example.com", true));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/oauth2/authorize?client_id=abc");
    assertThat(
            request
                .getSession(false)
                .getAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE))
        .isNull();
    verifyNoInteractions(platformUseCase);
    // New-device login email notification — fired after a successful social login too.
    verify(recordLoginDevice).handle(any());
  }

  @Test
  void tenantConfirmationRequiredRedirectsToTheConfirmationPage() throws Exception {
    UUID organizationId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    when(tenantUseCase.handle(any()))
        .thenReturn(new AuthenticateWithSocialProviderResult.ConfirmationRequired());

    handler.onAuthenticationSuccess(request, response, googleToken("user@example.com", true));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/login/social/confirmation-required");
    verify(tenantSessions, never()).establishViaSocialLogin(any(), any(), any(), any(), any());
    verifyNoInteractions(recordLoginDevice);
  }

  @Test
  void tenantLoginNotAllowedRedirectsBackToLoginWithAnError() throws Exception {
    UUID organizationId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    when(tenantUseCase.handle(any()))
        .thenThrow(
            new SocialLoginNotAllowedException(
                new OrganizationId(organizationId),
                com.clavaris.identity.domain.model.SocialProvider.GOOGLE));

    handler.onAuthenticationSuccess(request, response, googleToken("user@example.com", true));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/login?socialLoginError");
  }

  @Test
  void treatsGoogleEmailVerifiedAsAStringTrueAsVerified() throws Exception {
    // Code review finding: OidcUser's own typed ClaimAccessor tolerates email_verified arriving
    // as either a native boolean or the JSON string "true" — a raw Boolean.TRUE.equals(...) check
    // would incorrectly treat this as unverified and never even call the use case.
    UUID organizationId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token-value")
            .subject("google-sub-123")
            .claim("email", "user@example.com")
            .claim("email_verified", "true")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    DefaultOidcUser principal =
        new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    OAuth2AuthenticationToken token =
        new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");

    AccountId accountId = AccountId.newId();
    when(tenantUseCase.handle(any()))
        .thenReturn(new AuthenticateWithSocialProviderResult.LoggedIn(accountId));
    when(tenantSessions.establishViaSocialLogin(any(), any(), eq(accountId.value()), any(), any()))
        .thenReturn("/o/" + organizationId + "/oauth2/authorize?client_id=abc");

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/oauth2/authorize?client_id=abc");
  }

  @Test
  void anUnresolvableRegistrationIdRedirectsWithoutEverCallingAnyUseCase() throws Exception {
    // Code review finding: ADR-0020 Decision 5 plans a future Microsoft provider — a registration
    // id with no matching SocialProvider enum constant must fail the same clean way every other
    // unresolvable-login case here does, not crash with an uncaught IllegalArgumentException.
    UUID organizationId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    Map<String, Object> attributes = new HashMap<>();
    attributes.put("id", "1");
    DefaultOAuth2User principal =
        new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
    OAuth2AuthenticationToken unknownProviderToken =
        new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "microsoft");

    handler.onAuthenticationSuccess(request, response, unknownProviderToken);

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/login?socialLoginError");
    verifyNoInteractions(tenantUseCase, platformUseCase);
  }

  @Test
  void anUnverifiedGoogleEmailRedirectsWithoutEverCallingTheUseCase() throws Exception {
    UUID organizationId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession()
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, googleToken("user@example.com", false));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/o/" + organizationId + "/login?socialLoginError");
    verifyNoInteractions(tenantUseCase);
  }

  @Test
  void aGitHubAccountWithNoVerifiedEmailRedirectsToThePlatformErrorPage() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, gitHubToken(null));

    assertThat(response.getRedirectedUrl()).isEqualTo("/platform/login?socialLoginError");
    verifyNoInteractions(platformUseCase);
  }

  @Test
  void platformLoginWithNoOrganizationSessionAttributeEstablishesAPlatformSession()
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    when(platformUseCase.handle(any()))
        .thenReturn(
            new AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn(platformAccountId));
    when(platformSessions.establish(any(), any(), eq(platformAccountId.value()), any()))
        .thenReturn("/platform/dashboard");

    handler.onAuthenticationSuccess(request, response, gitHubToken("founder@example.com"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/platform/dashboard");
    verifyNoInteractions(tenantUseCase);
  }

  @Test
  void platformConfirmationRequiredRedirectsToThePlatformConfirmationPage() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    when(platformUseCase.handle(any()))
        .thenReturn(new AuthenticatePlatformAccountWithSocialProviderResult.ConfirmationRequired());

    handler.onAuthenticationSuccess(request, response, gitHubToken("founder@example.com"));

    assertThat(response.getRedirectedUrl())
        .isEqualTo("/platform/login/social/confirmation-required");
  }
}
