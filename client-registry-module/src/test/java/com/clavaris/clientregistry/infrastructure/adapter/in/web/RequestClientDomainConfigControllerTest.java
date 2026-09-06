package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.HostnameAlreadyClaimedException;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.RequestClientDomainConfigResult;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.RequestClientDomainConfigUseCase;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as SetClientBrandingControllerTest's own Javadoc. */
class RequestClientDomainConfigControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private RequestClientDomainConfigUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RequestClientDomainConfigUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new RequestClientDomainConfigController(useCase)).build();
  }

  @Test
  void returns200WithThePendingDomainRequest() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    ClientDomainConfig config =
        ClientDomainConfig.request(
            oauthClientId, ClientDomainMode.CNAME, "login.example.com", null);
    when(useCase.handle(any())).thenReturn(new RequestClientDomainConfigResult(config));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/domain-config")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CNAME\",\"hostname\":\"login.example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauthClientId").value(oauthClientId.toString()))
        .andExpect(jsonPath("$.verificationStatus").value("PENDING"));
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
                    + "/domain-config")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CNAME\",\"hostname\":\"login.example.com\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheHostnameIsAlreadyClaimed() throws Exception {
    when(useCase.handle(any())).thenThrow(new HostnameAlreadyClaimedException("taken.example.com"));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/domain-config")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CNAME\",\"hostname\":\"taken.example.com\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void returns400WhenTheHostnameFailsValidation() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new IllegalArgumentException("hostname must be a valid DNS hostname"));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/domain-config")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CNAME\",\"hostname\":\"not a hostname\"}"))
        .andExpect(status().isBadRequest());
  }
}
