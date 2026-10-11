package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * "Always show account selector prompt": only an Organization that asked for it gets the prompt.
 */
class AccountSelectorAuthorizationRequestResolverTest {

  private final UUID organizationId = UUID.randomUUID();
  private SocialConnectionSettingsRepository settings;
  private AccountSelectorAuthorizationRequestResolver resolver;

  @BeforeEach
  void setUp() {
    settings = mock(SocialConnectionSettingsRepository.class);
    when(settings.findByOrganizationIdAndProvider(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.empty());
    final ClientRegistration google =
        ClientRegistration.withRegistrationId("google")
            .clientId("shared-client")
            .clientSecret("shared-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "email")
            .authorizationUri("https://accounts.example/authorize")
            .tokenUri("https://accounts.example/token")
            .build();
    resolver =
        new AccountSelectorAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(google), settings);
  }

  private MockHttpServletRequest tenantRequest() {
    final MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/google");
    request
        .getSession(true)
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    return request;
  }

  private void chooserIs(final boolean on) {
    when(settings.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE))
        .thenReturn(
            Optional.of(
                SocialConnectionSettings.define(organizationId, SocialProvider.GOOGLE, false, on)));
  }

  @Test
  void anOrganizationThatAskedForTheChooserGetsThePromptOnTheRedirect() {
    chooserIs(true);

    final OAuth2AuthorizationRequest result = resolver.resolve(tenantRequest(), "google");

    assertThat(result.getAdditionalParameters()).containsEntry("prompt", "select_account");
    assertThat(result.getAuthorizationRequestUri()).contains("prompt=select_account");
  }

  @Test
  void anOrganizationThatDidNotAskGetsTheOrdinaryRedirect() {
    chooserIs(false);

    final OAuth2AuthorizationRequest result = resolver.resolve(tenantRequest(), "google");

    assertThat(result.getAdditionalParameters()).doesNotContainKey("prompt");
  }

  // A platform login carries no Organization, so it is never given the prompt.
  @Test
  void aLoginWithNoOrganizationInTheSessionIsLeftAlone() {
    chooserIs(true);
    final MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/google");
    request.getSession(true);

    assertThat(resolver.resolve(request, "google").getAdditionalParameters())
        .doesNotContainKey("prompt");
  }

  @Test
  void aMalformedOrganizationInTheSessionIsLeftAlone() {
    final MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/google");
    request
        .getSession(true)
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE, "not-a-uuid");

    assertThat(resolver.resolve(request, "google").getAdditionalParameters())
        .doesNotContainKey("prompt");
  }

  // The resolver adds the prompt and nothing else: a registration that does not exist is Spring's
  // own refusal, passed through untouched rather than swallowed.
  @Test
  void aRegistrationThatDoesNotExistIsRefusedAsSpringRefusesIt() {
    final MockHttpServletRequest request = tenantRequest();

    assertThatThrownBy(() -> resolver.resolve(request, "facebook"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theRequestFormResolvesTheRegistrationFromThePath() {
    chooserIs(true);
    final MockHttpServletRequest request = tenantRequest();
    request.setServletPath("/oauth2/authorization/google");
    request.setRequestURI("/oauth2/authorization/google");

    final OAuth2AuthorizationRequest result = resolver.resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getAdditionalParameters()).containsEntry("prompt", "select_account");
  }
}
