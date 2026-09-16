package com.clavaris.clientregistry.application.usecase.getclientbranding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.setclientbranding.ClientBrandingRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetClientBrandingServiceTest {

  private OAuthClientRepository oauthClients;
  private ClientBrandingRepository brandings;
  private GetClientBrandingService service;

  @BeforeEach
  void setUp() {
    oauthClients = mock(OAuthClientRepository.class);
    brandings = mock(ClientBrandingRepository.class);
    service = new GetClientBrandingService(oauthClients, brandings);
  }

  private OAuthClient registeredClient(final UUID organizationId) {
    return OAuthClient.register(
        organizationId,
        "test_client",
        "hashed-secret",
        List.of("https://app.example.com/callback"),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void returnsTheConfiguredBrandingWhenOneExists() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    ClientBranding existing =
        ClientBranding.define(client.id(), "https://cdn.example.com/logo.png", null, null);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(brandings.findByOAuthClientId(client.id())).thenReturn(Optional.of(existing));

    ClientBranding result = service.handle(organizationId, client.id());

    assertThat(result).isEqualTo(existing);
  }

  @Test
  void returnsUnconfiguredDefaultsWhenNoBrandingHasEverBeenSet() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client = registeredClient(organizationId);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    when(brandings.findByOAuthClientId(client.id())).thenReturn(Optional.empty());

    ClientBranding result = service.handle(organizationId, client.id());

    assertThat(result.oauthClientId()).isEqualTo(client.id());
    assertThat(result.logoUrl()).isEmpty();
    assertThat(result.primaryColor()).isEmpty();
    assertThat(result.applicationDisplayName()).isEmpty();
  }

  @Test
  void rejectsANonExistentOAuthClient() {
    UUID organizationId = UUID.randomUUID();
    UUID nonExistentClientId = UUID.randomUUID();
    when(oauthClients.findById(nonExistentClientId)).thenReturn(Optional.empty());

    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(organizationId, nonExistentClientId));
  }

  // SDE-III review, 2026-09-15 — the real regression this guards: before this fix, this method
  // never even accepted an organizationId, so a caller pairing a valid organizationId with a
  // different Organization's own oauthClientId would read back that Organization's real branding.
  // See OAuthClientNotFoundException's own Javadoc for the "collapse into a 404" discipline BR-
  // ORG-02 already establishes elsewhere.
  @Test
  void rejectsAClientThatBelongsToADifferentOrganization() {
    OAuthClient client = registeredClient(UUID.randomUUID());
    UUID unrelatedOrganizationId = UUID.randomUUID();
    UUID clientId = client.id();
    when(oauthClients.findById(clientId)).thenReturn(Optional.of(client));

    // java:S5778 (SDE-III review, 2026-09-16): clientId resolved above, outside the lambda, so
    // exactly one invocation that could throw remains inside the assertion — the one actually under
    // test.
    assertThatExceptionOfType(OAuthClientNotFoundException.class)
        .isThrownBy(() -> service.handle(unrelatedOrganizationId, clientId));
  }
}
