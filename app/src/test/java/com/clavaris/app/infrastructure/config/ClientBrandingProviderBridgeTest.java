package com.clavaris.app.infrastructure.config;

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
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
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
  private final ClientBrandingProviderBridge bridge =
      new ClientBrandingProviderBridge(oauthClients, getClientBranding);

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  @Test
  void resolvesUnconfiguredForANullClientId() {
    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, null);

    assertThat(snapshot.logoUrl()).isEmpty();
    assertThat(snapshot.primaryColor()).isEmpty();
    assertThat(snapshot.applicationDisplayName()).isEmpty();
    verify(getClientBranding, never()).handle(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resolvesUnconfiguredForAnUnknownClientId() {
    when(oauthClients.findByClientId("unknown-client")).thenReturn(Optional.empty());

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "unknown-client");

    assertThat(snapshot.logoUrl()).isEmpty();
    verify(getClientBranding, never()).handle(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resolvesUnconfiguredForAClientBelongingToADifferentOrganization() {
    OAuthClient client = anOAuthClient(UUID.randomUUID());
    when(oauthClients.findByClientId("cross-tenant-client")).thenReturn(Optional.of(client));

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "cross-tenant-client");

    assertThat(snapshot.logoUrl()).isEmpty();
    verify(getClientBranding, never()).handle(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resolvesTheRealBrandingForAClientInTheSameOrganization() {
    OAuthClient client = anOAuthClient(organizationId.value());
    when(oauthClients.findByClientId("branded-client")).thenReturn(Optional.of(client));
    ClientBranding branding =
        ClientBranding.define(
            client.id(), "https://cdn.example.com/logo.png", "#336699", "Acme Corp");
    when(getClientBranding.handle(client.id())).thenReturn(branding);

    ClientBrandingSnapshot snapshot = bridge.brandingFor(organizationId, "branded-client");

    assertThat(snapshot.logoUrl()).contains("https://cdn.example.com/logo.png");
    assertThat(snapshot.primaryColor()).contains("#336699");
    assertThat(snapshot.applicationDisplayName()).contains("Acme Corp");
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
