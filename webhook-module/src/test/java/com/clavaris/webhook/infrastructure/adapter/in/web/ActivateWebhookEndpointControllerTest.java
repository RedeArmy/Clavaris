package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.webhook.application.usecase.activatewebhookendpoint.ActivateWebhookEndpointUseCase;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointNotFoundException;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc pattern as {@link RegisterWebhookEndpointControllerTest}. */
class ActivateWebhookEndpointControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private ActivateWebhookEndpointUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ActivateWebhookEndpointUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ActivateWebhookEndpointController(useCase)).build();
  }

  @Test
  void returns200WithTheReactivatedEndpoint() throws Exception {
    UUID endpointId = UUID.randomUUID();
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            UUID.randomUUID(), "https://example.com", null, List.of("x"), "secret");
    when(useCase.handle(any())).thenReturn(endpoint);

    mockMvc
        .perform(
            post("/api/v1/admin/webhook-endpoints/" + endpointId + ":activate")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void returns404WhenTheEndpointDoesNotExist() throws Exception {
    UUID endpointId = UUID.randomUUID();
    when(useCase.handle(any())).thenThrow(new WebhookEndpointNotFoundException(endpointId));

    mockMvc
        .perform(
            post("/api/v1/admin/webhook-endpoints/" + endpointId + ":activate")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }
}
