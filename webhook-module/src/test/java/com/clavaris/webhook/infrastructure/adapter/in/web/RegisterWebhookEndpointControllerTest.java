package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.OrganizationNotFoundException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointCommand;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointResult;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.RegisterWebhookEndpointUseCase;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern as client-registry-module's
 * RegisterOAuthClientControllerTest.
 */
class RegisterWebhookEndpointControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID organizationId = UUID.randomUUID();
  private RegisterWebhookEndpointUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RegisterWebhookEndpointUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new RegisterWebhookEndpointController(useCase)).build();
  }

  private String path() {
    return "/api/v1/admin/organizations/" + organizationId + "/webhook-endpoints";
  }

  @Test
  void returns201WithTheGeneratedSigningSecret() throws Exception {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId,
            "https://example.com/webhooks",
            "Prod",
            List.of("account.created"),
            "encrypted-secret");
    when(useCase.handle(any()))
        .thenReturn(new RegisterWebhookEndpointResult(endpoint, "the-raw-secret"));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"url\":\"https://example.com/webhooks\","
                        + "\"subscribedEventTypes\":[\"account.created\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.signingSecret").value("the-raw-secret"))
        .andExpect(jsonPath("$.endpoint.url").value("https://example.com/webhooks"))
        .andExpect(jsonPath("$.endpoint.organizationId").value(organizationId.toString()));
  }

  @Test
  void neverExposesAnEncryptedSecretInTheResponseBody() throws Exception {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId, "https://example.com", null, List.of("x"), "encrypted-secret");
    when(useCase.handle(any())).thenReturn(new RegisterWebhookEndpointResult(endpoint, "raw"));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\",\"subscribedEventTypes\":[\"x\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.endpoint.currentSecretEncrypted").doesNotExist());
  }

  @Test
  void rejectsAnEmptySubscribedEventTypesListWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\",\"subscribedEventTypes\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(organizationId));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\",\"subscribedEventTypes\":[\"x\"]}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void theCallingPlatformClientBecomesTheAuditActor() throws Exception {
    WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            organizationId, "https://example.com", null, List.of("x"), "encrypted-secret");
    when(useCase.handle(any())).thenReturn(new RegisterWebhookEndpointResult(endpoint, "raw"));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\",\"subscribedEventTypes\":[\"x\"]}"))
        .andExpect(status().isCreated());

    ArgumentCaptor<RegisterWebhookEndpointCommand> captor =
        ArgumentCaptor.forClass(RegisterWebhookEndpointCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().actor().id()).isEqualTo("test-platform-client");
  }
}
