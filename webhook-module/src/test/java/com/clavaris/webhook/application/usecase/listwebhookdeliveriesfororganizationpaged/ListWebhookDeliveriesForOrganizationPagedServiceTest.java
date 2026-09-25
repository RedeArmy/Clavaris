package com.clavaris.webhook.application.usecase.listwebhookdeliveriesfororganizationpaged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryRepository;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWebhookDeliveriesForOrganizationPagedServiceTest {

  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final ListWebhookDeliveriesForOrganizationPagedService service =
      new ListWebhookDeliveriesForOrganizationPagedService(deliveries);

  @Test
  void delegatesToTheRepositoryWithTheGivenOrganizationAndPageRequest() {
    UUID organizationId = UUID.randomUUID();
    KeysetPageRequest pageRequest = KeysetPageRequest.first();
    KeysetPage<WebhookDelivery> page = new KeysetPage<>(List.of(), null, null, false, false);
    when(deliveries.findKeysetPageByOrganizationId(organizationId, pageRequest)).thenReturn(page);

    KeysetPage<WebhookDelivery> result =
        service.handle(
            new ListWebhookDeliveriesForOrganizationPagedQuery(organizationId, pageRequest));

    assertThat(result).isSameAs(page);
  }
}
