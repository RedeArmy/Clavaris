package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.ClientDomainConfigNotFoundException;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.OAuthClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.VerifyClientDomainOwnershipResult;
import com.clavaris.clientregistry.application.usecase.verifyclientdomainownership.VerifyClientDomainOwnershipUseCase;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VerifyClientDomainOwnershipControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private VerifyClientDomainOwnershipUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(VerifyClientDomainOwnershipUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new VerifyClientDomainOwnershipController(useCase)).build();
  }

  @Test
  void returns200WithAVerifiedOutcome() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    ClientDomainConfig verified =
        ClientDomainConfig.request(oauthClientId, ClientDomainMode.CNAME, "login.example.com", null)
            .markVerified();
    when(useCase.handle(any())).thenReturn(new VerifyClientDomainOwnershipResult(verified));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/domain-config:verify-ownership")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
  }

  @Test
  void returns200WithAFailedOutcomeRatherThanAnError() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    ClientDomainConfig failed =
        ClientDomainConfig.request(oauthClientId, ClientDomainMode.CNAME, "login.example.com", null)
            .markFailed();
    when(useCase.handle(any())).thenReturn(new VerifyClientDomainOwnershipResult(failed));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/domain-config:verify-ownership")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("FAILED"));
  }

  @Test
  void returns404WhenTheOAuthClientDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OAuthClientNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/domain-config:verify-ownership")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns404WhenNoDomainHasEverBeenRequested() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new ClientDomainConfigNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + UUID.randomUUID()
                    + "/clients/"
                    + UUID.randomUUID()
                    + "/domain-config:verify-ownership")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }
}
