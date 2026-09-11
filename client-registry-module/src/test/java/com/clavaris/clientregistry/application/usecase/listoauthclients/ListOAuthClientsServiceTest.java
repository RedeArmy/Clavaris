package com.clavaris.clientregistry.application.usecase.listoauthclients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListOAuthClientsServiceTest {

  private final OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
  private final ListOAuthClientsService service = new ListOAuthClientsService(oauthClients);

  @Test
  void delegatesStraightToTheRepository() {
    UUID organizationId = UUID.randomUUID();
    OAuthClient client =
        OAuthClient.register(
            organizationId,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://jobseeker.example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"),
            true,
            List.of());
    when(oauthClients.findAllByOrganizationId(organizationId)).thenReturn(List.of(client));

    assertThat(service.handle(organizationId)).containsExactly(client);
  }

  @Test
  void anEmptyListIsAValidAnswer() {
    UUID organizationId = UUID.randomUUID();
    when(oauthClients.findAllByOrganizationId(organizationId)).thenReturn(List.of());

    assertThat(service.handle(organizationId)).isEmpty();
  }
}
