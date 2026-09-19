package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.impersonateaccount.OAuthClientSummary;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthClientsForOrganizationProviderBridgeTest {

  private final ListOAuthClientsUseCase listOAuthClients = mock(ListOAuthClientsUseCase.class);
  private final OAuthClientsForOrganizationProviderBridge bridge =
      new OAuthClientsForOrganizationProviderBridge(listOAuthClients);

  @Test
  void mapsEveryActiveClientToASummary() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient active = anOAuthClient(organizationId, "jobseeker-web", true);
    OAuthClient inactive = anOAuthClient(organizationId, "jobseeker-mobile", false);
    when(listOAuthClients.handle(organizationId)).thenReturn(List.of(active, inactive));

    List<OAuthClientSummary> summaries = bridge.forOrganization(new OrganizationId(organizationId));

    assertThat(summaries)
        .as(
            "an inactive client can never be impersonated-as — filtered out before the caller sees it")
        .extracting(OAuthClientSummary::clientId)
        .containsExactly("jobseeker-web");
    assertThat(summaries.get(0).id()).isEqualTo(active.id());
    assertThat(summaries.get(0).allowedScopes()).isEqualTo(active.allowedScopes());
  }

  @Test
  void returnsAnEmptyListWhenTheOrganizationHasNoClients() {
    UUID organizationId = UUID.randomUUID();
    when(listOAuthClients.handle(organizationId)).thenReturn(List.of());

    assertThat(bridge.forOrganization(new OrganizationId(organizationId))).isEmpty();
  }

  private static OAuthClient anOAuthClient(
      final UUID organizationId, final String clientId, final boolean active) {
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            clientId,
            "argon2id$hashed",
            List.of("https://app.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    return active ? client : client.deactivate();
  }
}
