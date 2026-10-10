package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.getclientbranding.GetClientBrandingUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0009 §3: proves the bridge's own client-resolution/cross-tenant logic, not
 * GetClientBrandingUseCase itself.
 */
class ClientBrandingProviderBridgeTest {

  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final GetClientBrandingUseCase getClientBranding = mock(GetClientBrandingUseCase.class);
  private final OrganizationNameProvider organizationNames = mock(OrganizationNameProvider.class);
  private final GetOrganizationProfilesUseCase getProfiles =
      mock(GetOrganizationProfilesUseCase.class);
  private final ClientBrandingProviderBridge bridge =
      new ClientBrandingProviderBridge(
          oauthClients, getClientBranding, organizationNames, getProfiles);

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private void organizationHasProfile(final OrganizationProfile profile) {
    when(getProfiles.handle(List.of(organizationId.value())))
        .thenReturn(Map.of(organizationId.value(), profile));
  }

  private OrganizationProfile profile() {
    return OrganizationProfile.empty(organizationId.value());
  }

  // What the Organization set for all its applications is the default; a client's own wins.
  @Test
  void anApplicationWithNoBrandingOfItsOwnUsesTheOrganizationsLogoAndColour() {
    final Instant uploaded = Instant.parse("2026-10-10T12:00:00Z");
    organizationHasProfile(
        profile().withDetails(null, null, "#2563eb").withLogoUpdatedAt(uploaded));

    final ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, null);

    assertThat(snapshot.primaryColor()).contains("#2563eb");
    assertThat(snapshot.logoUrl())
        .contains("/o/" + organizationId.value() + "/branding/logo?v=" + uploaded.toEpochMilli());
  }

  @Test
  void aClientsOwnLogoAndColourWinOverTheOrganizations() {
    OAuthClient client = anOAuthClient(organizationId.value());
    when(oauthClients.findByClientId("branded-client")).thenReturn(Optional.of(client));
    when(getClientBranding.handle(organizationId.value(), client.id()))
        .thenReturn(
            ClientBranding.define(
                client.id(), "https://cdn.example.com/logo.png", "#336699", null));
    organizationHasProfile(
        profile().withDetails(null, null, "#2563eb").withLogoUpdatedAt(Instant.now()));

    final ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "branded-client");

    assertThat(snapshot.logoUrl()).contains("https://cdn.example.com/logo.png");
    assertThat(snapshot.primaryColor()).contains("#336699");
  }

  @Test
  void eachValueFallsBackOnItsOwn() {
    OAuthClient client = anOAuthClient(organizationId.value());
    when(oauthClients.findByClientId("colour-only")).thenReturn(Optional.of(client));
    when(getClientBranding.handle(organizationId.value(), client.id()))
        .thenReturn(ClientBranding.define(client.id(), null, "#336699", null));
    organizationHasProfile(profile().withLogoUpdatedAt(Instant.ofEpochMilli(5)));

    final ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "colour-only");

    assertThat(snapshot.primaryColor()).contains("#336699");
    assertThat(snapshot.logoUrl()).isPresent();
  }

  @Test
  void aProfileWithNoLogoOffersNone() {
    organizationHasProfile(profile().withDetails("About us", null, null));

    assertThat(bridge.brandingFor(organizationId, null).logoUrl()).isEmpty();
  }

  // The name the Organization chose for its application is resolved by the name provider (it also
  // feeds the emails), so the page and the emails cannot disagree.
  @Test
  void theApplicationNameComesFromTheNameProvider() {
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Portal"));

    assertThat(bridge.brandingFor(organizationId, null).applicationDisplayName())
        .contains("Acme Portal");
  }

  @Test
  void resolvesUnconfiguredForANullClientId() {
    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, null);

    assertThat(snapshot.logoUrl()).isEmpty();
    assertThat(snapshot.primaryColor()).isEmpty();
    assertThat(snapshot.applicationDisplayName()).isEmpty();
    verify(getClientBranding, never())
        .handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resolvesUnconfiguredForAnUnknownClientId() {
    when(oauthClients.findByClientId("unknown-client")).thenReturn(Optional.empty());

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "unknown-client");

    assertThat(snapshot.logoUrl()).isEmpty();
    verify(getClientBranding, never())
        .handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resolvesUnconfiguredForAClientBelongingToADifferentOrganization() {
    OAuthClient client = anOAuthClient(UUID.randomUUID());
    when(oauthClients.findByClientId("cross-tenant-client")).thenReturn(Optional.of(client));

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "cross-tenant-client");

    assertThat(snapshot.logoUrl()).isEmpty();
    verify(getClientBranding, never())
        .handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resolvesTheRealBrandingForAClientInTheSameOrganization() {
    OAuthClient client = anOAuthClient(organizationId.value());
    when(oauthClients.findByClientId("branded-client")).thenReturn(Optional.of(client));
    ClientBranding branding =
        ClientBranding.define(
            client.id(), "https://cdn.example.com/logo.png", "#336699", "Acme Corp");
    when(getClientBranding.handle(organizationId.value(), client.id())).thenReturn(branding);

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "branded-client");

    assertThat(snapshot.logoUrl()).contains("https://cdn.example.com/logo.png");
    assertThat(snapshot.primaryColor()).contains("#336699");
    assertThat(snapshot.applicationDisplayName()).contains("Acme Corp");
  }

  // The pages this feeds are the consuming application's: with no display name of its own they
  // show its Organization's name, never Clavaris.
  @Test
  void anApplicationWithNoDisplayNameIsShownUnderItsOrganizationsName() {
    OAuthClient client = anOAuthClient(organizationId.value());
    when(oauthClients.findByClientId("plain-client")).thenReturn(Optional.of(client));
    when(getClientBranding.handle(organizationId.value(), client.id()))
        .thenReturn(
            ClientBranding.define(client.id(), "https://cdn.example.com/logo.png", null, null));
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Analytics"));

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "plain-client");

    assertThat(snapshot.applicationDisplayName()).contains("Acme Analytics");
    assertThat(snapshot.logoUrl()).contains("https://cdn.example.com/logo.png");
  }

  @Test
  void anApplicationsOwnDisplayNameWinsOverItsOrganizationsName() {
    OAuthClient client = anOAuthClient(organizationId.value());
    when(oauthClients.findByClientId("branded-client")).thenReturn(Optional.of(client));
    when(getClientBranding.handle(organizationId.value(), client.id()))
        .thenReturn(ClientBranding.define(client.id(), null, null, "Acme Dashboard"));
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Analytics"));

    assertThat(bridge.brandingFor(organizationId, "branded-client").applicationDisplayName())
        .contains("Acme Dashboard");
  }

  @Test
  void withNoClientAtAllThePageIsStillShownUnderTheOrganizationsName() {
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Analytics"));

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, null);

    assertThat(snapshot.applicationDisplayName()).contains("Acme Analytics");
    assertThat(snapshot.logoUrl()).isEmpty();
  }

  // A client of another Organization is treated as unknown: the name shown is the path's own
  // Organization's, never the other one's.
  @Test
  void aClientOfAnotherOrganizationGetsThisOrganizationsNameAndNothingOfTheOthers() {
    OAuthClient other = anOAuthClient(UUID.randomUUID());
    when(oauthClients.findByClientId("cross-tenant-client")).thenReturn(Optional.of(other));
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Analytics"));

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "cross-tenant-client");

    assertThat(snapshot.applicationDisplayName()).contains("Acme Analytics");
    assertThat(snapshot.logoUrl()).isEmpty();
  }

  private static OAuthClient anOAuthClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "a-client",
        "argon2id$hashed",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }
}
