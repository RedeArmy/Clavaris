package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.webhook.application.usecase.replaywebhookdelivery.ReplayWebhookDeliveryUseCase;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotFoundException;
import com.clavaris.webhook.application.usecase.replaywebhookdelivery.WebhookDeliveryNotReplayableException;
import com.clavaris.webhook.domain.model.WebhookDelivery;
import com.clavaris.webhook.domain.model.WebhookDeliveryStatus;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc pattern as {@link RegisterWebhookEndpointControllerTest}. */
class ReplayWebhookDeliveryControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private ReplayWebhookDeliveryUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ReplayWebhookDeliveryUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ReplayWebhookDeliveryController(useCase)).build();
  }

  private String path(final UUID endpointId, final UUID deliveryId) {
    return "/api/v1/admin/webhook-endpoints/"
        + endpointId
        + "/deliveries/"
        + deliveryId
        + ":replay";
  }

  @Test
  void returns200WithTheDeliveryResetToPending() throws Exception {
    UUID endpointId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    WebhookDelivery replayed =
        WebhookDelivery.schedule(
            endpointId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Account",
            UUID.randomUUID(),
            "account.created",
            "{}",
            null);
    when(useCase.handle(any())).thenReturn(replayed);

    mockMvc
        .perform(post(path(endpointId, deliveryId)).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void returns404WhenTheDeliveryDoesNotExist() throws Exception {
    UUID endpointId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    when(useCase.handle(any())).thenThrow(new WebhookDeliveryNotFoundException(deliveryId));

    mockMvc
        .perform(post(path(endpointId, deliveryId)).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheDeliveryIsNotInATerminalState() throws Exception {
    UUID endpointId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    when(useCase.handle(any()))
        .thenThrow(
            new WebhookDeliveryNotReplayableException(deliveryId, WebhookDeliveryStatus.PENDING));

    mockMvc
        .perform(post(path(endpointId, deliveryId)).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
