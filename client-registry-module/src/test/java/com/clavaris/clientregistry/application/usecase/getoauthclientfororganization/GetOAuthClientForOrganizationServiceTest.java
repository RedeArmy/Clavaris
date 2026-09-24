package com.clavaris.clientregistry.application.usecase.getoauthclientfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetOAuthClientForOrganizationServiceTest {

  private final UUID organizationId = UUID.randomUUID();
  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final GetOAuthClientForOrganizationService service =
      new GetOAuthClientForOrganizationService(oauthClients);

  private OAuthClient sampleClient() {
    return OAuthClient.register(
        organizationId,
        "target-client",
        "argon2id$hashed",
        List.of(),
        List.of("authorization_code"),
        List.of("openid"),
        true,
        List.of());
  }

  @Test
  void returnsTheClientWhenItBelongsToTheGivenOrganization() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    Optional<OAuthClient> result =
        service.handle(new GetOAuthClientForOrganizationQuery("target-client", organizationId));

    assertThat(result).contains(existing);
  }

  @Test
  void returnsEmptyForAnUnknownClientId() {
    when(oauthClients.findByClientId("ghost-client")).thenReturn(Optional.empty());

    Optional<OAuthClient> result =
        service.handle(new GetOAuthClientForOrganizationQuery("ghost-client", organizationId));

    assertThat(result).isEmpty();
  }

  // Same anti-enumeration reasoning DeactivateOAuthClientServiceTest's own identical test
  // documents — a cross-tenant mismatch collapses into the same empty result a genuinely
  // missing clientId already produces.
  @Test
  void returnsEmptyForAClientThatBelongsToADifferentOrganization() {
    OAuthClient existing = sampleClient();
    when(oauthClients.findByClientId("target-client")).thenReturn(Optional.of(existing));

    Optional<OAuthClient> result =
        service.handle(new GetOAuthClientForOrganizationQuery("target-client", UUID.randomUUID()));

    assertThat(result).isEmpty();
  }
}
