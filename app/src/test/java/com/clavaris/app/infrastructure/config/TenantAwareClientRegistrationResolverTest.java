package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ADR-0022: unit-level proof of {@code TenantAwareClientRegistrationResolver}'s own resolution
 * logic — tenant-own credential, fallback-to-shared, and the two "no organization context" cases
 * (no request at all; a request with no session attribute set) — without needing a real OAuth2
 * login round trip, which {@code SocialLoginIntegrationTest} already covers end to end.
 */
class TenantAwareClientRegistrationResolverTest {

  private OrganizationSocialCredentialRepository credentials;
  private OrganizationSocialCredentialCipher cipher;
  private TenantAwareClientRegistrationResolver repository;

  @BeforeEach
  void setUp() {
    // Same relaxed-binding property keys application.yml itself uses — MockEnvironment +
    // TenantAwareClientRegistrationResolver's own Binder-based construction reads these exactly
    // the way the real running app does, no hand-built OAuth2ClientProperties object.
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "spring.security.oauth2.client.registration.google.client-id", "shared-google-client-id");
    environment.setProperty(
        "spring.security.oauth2.client.registration.google.client-secret",
        "shared-google-client-secret");
    environment.setProperty(
        "spring.security.oauth2.client.registration.github.client-id", "shared-github-client-id");
    environment.setProperty(
        "spring.security.oauth2.client.registration.github.client-secret",
        "shared-github-client-secret");
    environment.setProperty(
        "spring.security.oauth2.client.registration.github.scope", "read:user,user:email");

    credentials = mock(OrganizationSocialCredentialRepository.class);
    cipher = mock(OrganizationSocialCredentialCipher.class);
    repository = new TenantAwareClientRegistrationResolver(environment, credentials, cipher);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void fallsBackToTheSharedRegistrationWhenNoRequestContextExists() {
    ClientRegistration registration = repository.findByRegistrationId("google");

    assertThat(registration.getClientId()).isEqualTo("shared-google-client-id");
    verifyNoInteractions(credentials, cipher);
  }

  @Test
  void fallsBackToTheSharedRegistrationWhenTheSessionCarriesNoOrganizationId() {
    withRequestHavingASession();

    ClientRegistration registration = repository.findByRegistrationId("google");

    assertThat(registration.getClientId()).isEqualTo("shared-google-client-id");
  }

  @Test
  void fallsBackToTheSharedRegistrationWhenTheOrganizationHasNotBroughtItsOwnCredentials() {
    UUID organizationId = UUID.randomUUID();
    withRequestCarryingOrganizationId(organizationId);
    when(credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE))
        .thenReturn(Optional.empty());

    ClientRegistration registration = repository.findByRegistrationId("google");

    assertThat(registration.getClientId()).isEqualTo("shared-google-client-id");
  }

  @Test
  void resolvesTheOrganizationsOwnCredentialWhenOneHasBeenSet() {
    UUID organizationId = UUID.randomUUID();
    withRequestCarryingOrganizationId(organizationId);
    OrganizationSocialCredential ownCredential =
        OrganizationSocialCredential.define(
            organizationId, SocialProvider.GOOGLE, "tenant-own-client-id", "encrypted-secret");
    when(credentials.findByOrganizationIdAndProvider(organizationId, SocialProvider.GOOGLE))
        .thenReturn(Optional.of(ownCredential));
    when(cipher.decrypt("encrypted-secret")).thenReturn("tenant-own-raw-secret");

    ClientRegistration registration = repository.findByRegistrationId("google");

    assertThat(registration.getClientId()).isEqualTo("tenant-own-client-id");
    assertThat(registration.getClientSecret()).isEqualTo("tenant-own-raw-secret");
    // Everything else (authorization-uri/token-uri/scope/redirect-uri-template) must be untouched —
    // only the credential itself differs from the shared registration.
    // CommonOAuth2Provider.GOOGLE's
    // own well-known endpoint, deterministic since no override exists for it in this test's
    // OAuth2ClientProperties fixture.
    assertThat(registration.getProviderDetails().getAuthorizationUri())
        .isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
  }

  @Test
  void anUnknownRegistrationIdResolvesToNull() {
    assertThat(repository.findByRegistrationId("not-a-real-provider")).isNull();
  }

  @Test
  void neverConsultsTenantCredentialsForARegistrationIdOutsideTheKnownProviders() {
    withRequestCarryingOrganizationId(UUID.randomUUID());

    repository.findByRegistrationId("not-a-real-provider");

    verifyNoInteractions(credentials, cipher);
  }

  private void withRequestHavingASession() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.getSession(true);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void withRequestCarryingOrganizationId(final UUID organizationId) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession(true)
        .setAttribute(
            SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE,
            organizationId.toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }
}
