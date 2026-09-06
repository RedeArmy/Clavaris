package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.setclientbranding.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingResult;
import com.clavaris.clientregistry.application.usecase.setclientbranding.SetClientBrandingUseCase;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as SetRedirectPolicyControllerTest's own Javadoc. */
class SetClientBrandingControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private SetClientBrandingUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(SetClientBrandingUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new SetClientBrandingController(useCase)).build();
  }

  @Test
  void returns200WithTheUpdatedBranding() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    ClientBranding branding =
        ClientBranding.define(oauthClientId, "https://cdn.example.com/logo.png", null, null);
    when(useCase.handle(any())).thenReturn(new SetClientBrandingResult(branding));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/branding")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"logoUrl\":\"https://cdn.example.com/logo.png\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauthClientId").value(oauthClientId.toString()))
        .andExpect(jsonPath("$.logoUrl").value("https://cdn.example.com/logo.png"));
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
                    + "/branding")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns400WhenBrandingContentFailsValidation() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new IllegalArgumentException("primaryColor must be a hex color"));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/branding")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryColor\":\"blue\"}"))
        .andExpect(status().isBadRequest());
  }
}
