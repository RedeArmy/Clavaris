package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.savesocialconnection.SocialConnectionSettingsRepository;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialConnectionSettings;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationSocialLoginPolicyProviderBridgeTest {

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final OrganizationSocialCredentialRepository credentials =
      mock(OrganizationSocialCredentialRepository.class);
  private final SocialConnectionSettingsRepository settings =
      mock(SocialConnectionSettingsRepository.class);
  private final OrganizationSocialLoginPolicyProviderBridge bridge =
      new OrganizationSocialLoginPolicyProviderBridge(organizations, credentials, settings);

  // "Block email subaddresses" is the Organization's own choice, saved per provider.
  @Test
  void theSavedChoiceToBlockSubaddressesIsReported() {
    UUID organizationId = UUID.randomUUID();
    when(settings.findByOrganizationIdAndProvider(
            organizationId, com.clavaris.organization.domain.model.SocialProvider.GOOGLE))
        .thenReturn(
            Optional.of(
                SocialConnectionSettings.define(
                    organizationId,
                    com.clavaris.organization.domain.model.SocialProvider.GOOGLE,
                    true,
                    false)));

    assertThat(
            bridge.blocksEmailSubaddresses(
                new OrganizationId(organizationId), SocialProvider.GOOGLE))
        .isTrue();
  }

  // No saved connection is how social login behaved before an Organization could choose.
  @Test
  void anOrganizationThatNeverSavedItsConnectionBlocksNothing() {
    UUID organizationId = UUID.randomUUID();
    when(settings.findByOrganizationIdAndProvider(
            organizationId, com.clavaris.organization.domain.model.SocialProvider.GITHUB))
        .thenReturn(Optional.empty());

    assertThat(
            bridge.blocksEmailSubaddresses(
                new OrganizationId(organizationId), SocialProvider.GITHUB))
        .isFalse();
  }

  private static Organization production(final String... providers) {
    return Organization.registerProductionEnvironment("Acme", UUID.randomUUID(), UUID.randomUUID())
        .withSocialLoginPolicy(true, List.of(providers));
  }

  private void hasOwnCredentials(
      final Organization organization,
      final com.clavaris.organization.domain.model.SocialProvider provider) {
    when(credentials.findByOrganizationIdAndProvider(organization.id(), provider))
        .thenReturn(
            Optional.of(
                OrganizationSocialCredential.define(
                    organization.id(), provider, "client-id", "encrypted")));
  }

  // A PRODUCTION Organization signs in only through its own OAuth app, never the shared one.
  @Test
  void aProductionProviderWithItsOwnCredentialsIsAllowed() {
    Organization organization = production("GOOGLE");
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    hasOwnCredentials(organization, com.clavaris.organization.domain.model.SocialProvider.GOOGLE);

    assertThat(
            bridge.isProviderAllowed(new OrganizationId(organization.id()), SocialProvider.GOOGLE))
        .isTrue();
  }

  @Test
  void aProductionProviderWithNoCredentialsIsNotAllowedEvenWhenSwitchedOn() {
    Organization organization = production("GOOGLE");
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    when(credentials.findByOrganizationIdAndProvider(
            organization.id(), com.clavaris.organization.domain.model.SocialProvider.GOOGLE))
        .thenReturn(Optional.empty());

    assertThat(
            bridge.isProviderAllowed(new OrganizationId(organization.id()), SocialProvider.GOOGLE))
        .isFalse();
  }

  @Test
  void onlyTheProductionProvidersWithCredentialsAreOffered() {
    Organization organization = production("GOOGLE", "GITHUB");
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));
    hasOwnCredentials(organization, com.clavaris.organization.domain.model.SocialProvider.GITHUB);

    assertThat(bridge.allowedProviders(new OrganizationId(organization.id())))
        .containsExactly(SocialProvider.GITHUB);
  }

  // A DEVELOPMENT sandbox always uses the shared app, so it needs no credentials of its own.
  @Test
  void aDevelopmentProviderNeedsNoCredentials() {
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organization.id())).thenReturn(Optional.of(organization));

    assertThat(
            bridge.isProviderAllowed(new OrganizationId(organization.id()), SocialProvider.GOOGLE))
        .isTrue();
    org.mockito.Mockito.verifyNoInteractions(credentials);
  }

  @Test
  void allowsAProviderTheOrganizationHasEnabled() {
    UUID organizationId = UUID.randomUUID();
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.isProviderAllowed(new OrganizationId(organizationId), SocialProvider.GOOGLE))
        .isTrue();
  }

  @Test
  void rejectsAProviderNotInTheAllowedList() {
    UUID organizationId = UUID.randomUUID();
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE"));
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.isProviderAllowed(new OrganizationId(organizationId), SocialProvider.GITHUB))
        .isFalse();
  }

  @Test
  void rejectsEverythingWhenSocialLoginItselfIsDisabled() {
    UUID organizationId = UUID.randomUUID();
    Organization organization = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.isProviderAllowed(new OrganizationId(organizationId), SocialProvider.GOOGLE))
        .isFalse();
  }

  @Test
  void treatsAnUnresolvableOrganizationAsNotAllowedRatherThanAnError() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.findById(unknownOrganizationId)).thenReturn(Optional.empty());

    assertThat(
            bridge.isProviderAllowed(
                new OrganizationId(unknownOrganizationId), SocialProvider.GOOGLE))
        .isFalse();
  }

  // Code review finding (TD-SEC-032, closed): allowedProviders() must resolve the whole set with
  // exactly one repository read, not one per known SocialProvider — the actual bug being fixed,
  // not just a behavioral parity check with isProviderAllowed().
  @Test
  void resolvesTheWholeAllowedSetWithExactlyOneRepositoryRead() {
    UUID organizationId = UUID.randomUUID();
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE", "GITHUB"));
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    Set<SocialProvider> allowed = bridge.allowedProviders(new OrganizationId(organizationId));

    assertThat(allowed).containsExactlyInAnyOrder(SocialProvider.GOOGLE, SocialProvider.GITHUB);
    verify(organizations, times(1)).findById(organizationId);
  }

  @Test
  void allowedProvidersIsEmptyWhenSocialLoginItselfIsDisabled() {
    UUID organizationId = UUID.randomUUID();
    Organization organization = Organization.register("Acme", UUID.randomUUID());
    when(organizations.findById(organizationId)).thenReturn(Optional.of(organization));

    assertThat(bridge.allowedProviders(new OrganizationId(organizationId))).isEmpty();
  }

  @Test
  void allowedProvidersIsEmptyForAnUnresolvableOrganization() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.findById(unknownOrganizationId)).thenReturn(Optional.empty());

    assertThat(bridge.allowedProviders(new OrganizationId(unknownOrganizationId))).isEmpty();
  }
}
