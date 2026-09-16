package com.clavaris.webhook.application.usecase.getwebhookendpointfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetWebhookEndpointForOrganizationServiceTest {

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final GetWebhookEndpointForOrganizationService service =
      new GetWebhookEndpointForOrganizationService(endpoints);

  @Test
  void delegatesStraightToTheRepositorysO1Lookup() {
    UUID organizationId = UUID.randomUUID();
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId, "https://example.com", null, List.of("x"), "secret");
    when(endpoints.findByIdAndOrganizationId(endpoint.id(), organizationId))
        .thenReturn(Optional.of(endpoint));

    Optional<WebhookEndpoint> result =
        service.handle(new GetWebhookEndpointForOrganizationQuery(organizationId, endpoint.id()));

    assertThat(result).contains(endpoint);
  }

  // TD-PERF-026: the whole point — this must never fall back to a full-organization scan.
  @Test
  void isEmptyForAnEndpointBelongingToADifferentOrganization() {
    UUID organizationId = UUID.randomUUID();
    UUID endpointId = UUID.randomUUID();
    when(endpoints.findByIdAndOrganizationId(endpointId, organizationId))
        .thenReturn(Optional.empty());

    Optional<WebhookEndpoint> result =
        service.handle(new GetWebhookEndpointForOrganizationQuery(organizationId, endpointId));

    assertThat(result).isEmpty();
  }
}
