package com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWebhookEndpointsForOrganizationServiceTest {

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final ListWebhookEndpointsForOrganizationService service =
      new ListWebhookEndpointsForOrganizationService(endpoints);

  @Test
  void delegatesStraightToTheRepository() {
    UUID organizationId = UUID.randomUUID();
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId, "https://example.com", null, List.of("x"), "secret");
    when(endpoints.findAllByOrganizationId(organizationId)).thenReturn(List.of(endpoint));

    List<WebhookEndpoint> result =
        service.handle(new ListWebhookEndpointsForOrganizationQuery(organizationId));

    assertThat(result).containsExactly(endpoint);
  }
}
