package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.webhook.application.usecase.listwebhookendpointsfororganization.ListWebhookEndpointsForOrganizationUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc pattern as {@link RegisterWebhookEndpointControllerTest}. */
class ListWebhookEndpointsControllerTest {

  private ListWebhookEndpointsForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ListWebhookEndpointsForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ListWebhookEndpointsController(useCase)).build();
  }

  @Test
  void returnsEveryEndpointRegisteredUnderTheOrganization() throws Exception {
    UUID organizationId = UUID.randomUUID();
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(organizationId, "https://example.com", null, List.of("x"), "s");
    when(useCase.handle(any())).thenReturn(List.of(endpoint));

    mockMvc
        .perform(get("/api/v1/admin/organizations/" + organizationId + "/webhook-endpoints"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].url").value("https://example.com"))
        .andExpect(jsonPath("$[0].currentSecretEncrypted").doesNotExist());
  }

  @Test
  void returnsAnEmptyListWhenNoneAreRegistered() throws Exception {
    UUID organizationId = UUID.randomUUID();
    when(useCase.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/admin/organizations/" + organizationId + "/webhook-endpoints"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }
}
