package com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWebhookDeliveriesForEndpointServiceTest {

  private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final ListWebhookDeliveriesForEndpointService service =
      new ListWebhookDeliveriesForEndpointService(endpoints, deliveries);

  @Test
  void returnsTheEndpointsDeliveryHistoryWhenTheEndpointExists() {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "secret");
    when(endpoints.findById(endpoint.id())).thenReturn(Optional.of(endpoint));
    WebhookDelivery delivery =
        WebhookDelivery.schedule(
            endpoint.id(),
            endpoint.organizationId(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "x",
            "{}",
            null);
    when(deliveries.findAllByEndpointId(endpoint.id(), 100)).thenReturn(List.of(delivery));

    List<WebhookDelivery> result =
        service.handle(new ListWebhookDeliveriesForEndpointQuery(endpoint.id()));

    assertThat(result).containsExactly(delivery);
  }

  @Test
  void rejectsAnUnknownEndpointWithoutQueryingDeliveries() {
    UUID unknownId = UUID.randomUUID();
    when(endpoints.findById(unknownId)).thenReturn(Optional.empty());
    ListWebhookDeliveriesForEndpointQuery query =
        new ListWebhookDeliveriesForEndpointQuery(unknownId);

    assertThatExceptionOfType(WebhookEndpointNotFoundException.class)
        .isThrownBy(() -> service.handle(query));

    verifyNoInteractions(deliveries);
  }
}
