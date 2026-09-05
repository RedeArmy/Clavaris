package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectUrlNotRegisteredException;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.SetRedirectPolicyForClientResult;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.SetRedirectPolicyForClientUseCase;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as SetRateLimitPolicyControllerTest's own Javadoc. */
class SetRedirectPolicyControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private SetRedirectPolicyForClientUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(SetRedirectPolicyForClientUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new SetRedirectPolicyController(useCase)).build();
  }

  @Test
  void returns200WithTheUpdatedPolicy() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    RedirectPolicy policy =
        RedirectPolicy.define(oauthClientId, "https://app.example.com/a", null, null, null);
    when(useCase.handle(any())).thenReturn(new SetRedirectPolicyForClientResult(policy));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/redirect-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fallbackSignInRedirectUrl\":\"https://app.example.com/a\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauthClientId").value(oauthClientId.toString()))
        .andExpect(jsonPath("$.fallbackSignInRedirectUrl").value("https://app.example.com/a"));
  }

  @Test
  void returns404WhenTheOAuthClientDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OAuthClientNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/redirect-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns400WhenAUrlIsNotARegisteredRedirectUri() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new RedirectUrlNotRegisteredException("https://not-registered.example.com"));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/redirect-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fallbackSignInRedirectUrl\":\"https://not-registered.example.com\"}"))
        .andExpect(status().isBadRequest());
  }
}
