package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.webhook.application.usecase.listwebhookdeliveriesforendpoint.ListWebhookDeliveriesForEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc pattern as {@link RegisterWebhookEndpointControllerTest}. */
class ListWebhookDeliveriesControllerTest {

  private ListWebhookDeliveriesForEndpointUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ListWebhookDeliveriesForEndpointUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ListWebhookDeliveriesController(useCase)).build();
  }

  @Test
  void returnsTheEndpointsDeliveryHistory() throws Exception {
    UUID endpointId = UUID.randomUUID();
    WebhookDelivery delivery =
        WebhookDelivery.schedule(
            endpointId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}");
    when(useCase.handle(any())).thenReturn(List.of(delivery));

    mockMvc
        .perform(get("/api/v1/admin/webhook-endpoints/" + endpointId + "/deliveries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].eventType").value("account.created"));
  }

  @Test
  void returns404WhenTheEndpointDoesNotExist() throws Exception {
    UUID endpointId = UUID.randomUUID();
    when(useCase.handle(any())).thenThrow(new WebhookEndpointNotFoundException(endpointId));

    mockMvc
        .perform(get("/api/v1/admin/webhook-endpoints/" + endpointId + "/deliveries"))
        .andExpect(status().isNotFound());
  }
}
