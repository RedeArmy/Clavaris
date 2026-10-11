package com.clavaris.organization.application.usecase.getsocialconnections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetSocialConnectionsServiceTest {

  private OrganizationRepository organizations;
  private OrganizationSocialCredentialRepository credentials;
  private SocialConnectionSettingsRepository settings;
  private GetSocialConnectionsService service;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    credentials = mock(OrganizationSocialCredentialRepository.class);
    settings = mock(SocialConnectionSettingsRepository.class);
    service =
        new GetSocialConnectionsService(organizations, credentials, settings, "https://id.example");
    when(credentials.findAllByOrganizationId(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    when(settings.findAllByOrganizationId(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
  }

  private Organization stored(final Organization organization) {
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    return organization;
  }

  private SocialConnection of(final SocialConnections connections, final SocialProvider provider) {
    return connections.connections().stream()
        .filter(connection -> connection.provider() == provider)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void everySupportedProviderIsListedEvenWhenNothingIsConfigured() {
    final Organization organization = stored(Organization.register("Acme", UUID.randomUUID()));

    final SocialConnections result = service.handle(organization.id());

    assertThat(result.environment()).isEqualTo(OrganizationEnvironment.DEVELOPMENT);
    assertThat(result.connections())
        .extracting(SocialConnection::provider)
        .containsExactly(SocialProvider.GOOGLE, SocialProvider.GITHUB);
    assertThat(result.connections())
        .allSatisfy(
            connection -> {
              assertThat(connection.status()).isEqualTo(SocialConnectionStatus.DISABLED);
              assertThat(connection.hasCredentials()).isFalse();
              assertThat(connection.clientId()).isEmpty();
              assertThat(connection.blockEmailSubaddresses()).isFalse();
            });
  }

  @Test
  void theRedirectUriIsTheSharedCallbackOfEachProvider() {
    final Organization organization = stored(Organization.register("Acme", UUID.randomUUID()));

    final SocialConnections result = service.handle(organization.id());

    assertThat(of(result, SocialProvider.GOOGLE).redirectUri())
        .isEqualTo("https://id.example/login/oauth2/code/google");
    assertThat(of(result, SocialProvider.GITHUB).redirectUri())
        .isEqualTo("https://id.example/login/oauth2/code/github");
  }

  // A DEVELOPMENT Organization uses the shared app, so being switched on is enough.
  @Test
  void aDevelopmentProviderThatIsSwitchedOnIsUsedForSignIn() {
    final Organization organization =
        stored(
            Organization.register("Acme", UUID.randomUUID())
                .withSocialLoginPolicy(true, List.of("GOOGLE")));

    final SocialConnections result = service.handle(organization.id());

    assertThat(of(result, SocialProvider.GOOGLE).status())
        .isEqualTo(SocialConnectionStatus.ENABLED);
    assertThat(of(result, SocialProvider.GITHUB).status())
        .isEqualTo(SocialConnectionStatus.DISABLED);
  }

  // A PRODUCTION Organization signs in only through its own app, so switched on is not enough.
  @Test
  void aProductionProviderWithNoCredentialsNeedsSetup() {
    final Organization organization =
        stored(
            Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
                .withSocialLoginPolicy(true, List.of("GOOGLE")));

    final SocialConnections result = service.handle(organization.id());

    assertThat(of(result, SocialProvider.GOOGLE).status())
        .isEqualTo(SocialConnectionStatus.SETUP_REQUIRED);
  }

  @Test
  void aProductionProviderWithCredentialsIsUsedForSignInAndShowsOnlyTheClientId() {
    final Organization organization =
        stored(
            Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
                .withSocialLoginPolicy(true, List.of("GOOGLE")));
    when(credentials.findAllByOrganizationId(organization.id()))
        .thenReturn(
            List.of(
                OrganizationSocialCredential.define(
                    organization.id(), SocialProvider.GOOGLE, "my-client", "encrypted")));
    when(settings.findAllByOrganizationId(organization.id()))
        .thenReturn(
            List.of(
                SocialConnectionSettings.define(
                    organization.id(), SocialProvider.GOOGLE, true, true)));

    final SocialConnection google = of(service.handle(organization.id()), SocialProvider.GOOGLE);

    assertThat(google.status()).isEqualTo(SocialConnectionStatus.ENABLED);
    assertThat(google.hasCredentials()).isTrue();
    assertThat(google.clientId()).contains("my-client");
    assertThat(google.blockEmailSubaddresses()).isTrue();
    assertThat(google.alwaysShowAccountSelector()).isTrue();
    assertThat(google.toString()).doesNotContain("encrypted");
  }

  @Test
  void anOrganizationThatDoesNotExistIsNotFound() {
    final UUID missing = UUID.randomUUID();
    when(organizations.findById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.handle(missing))
        .isInstanceOf(OrganizationNotFoundException.class);
  }
}
