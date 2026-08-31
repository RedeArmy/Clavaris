package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization.UnknownSocialProviderException;
import com.clavaris.organization.domain.model.Organization;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as SetRateLimitPolicyControllerTest's own Javadoc. */
class SetSocialLoginPolicyControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private SetSocialLoginPolicyForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(SetSocialLoginPolicyForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new SetSocialLoginPolicyController(useCase)).build();
  }

  @Test
  void returns200WithTheUpdatedPolicy() throws Exception {
    UUID organizationId = UUID.randomUUID();
    Organization organization =
        Organization.register("Acme", UUID.randomUUID())
            .withSocialLoginPolicy(true, List.of("GOOGLE", "GITHUB"));
    when(useCase.handle(any()))
        .thenReturn(new SetSocialLoginPolicyForOrganizationResult(organization));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + organizationId + "/social-login-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"providers\":[\"GOOGLE\",\"GITHUB\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.socialLoginEnabled").value(true))
        .andExpect(jsonPath("$.allowedSocialProviders[0]").value("GOOGLE"))
        .andExpect(jsonPath("$.allowedSocialProviders[1]").value("GITHUB"));
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/social-login-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"providers\":[\"GOOGLE\"]}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns400WhenAProviderIsUnknown() throws Exception {
    when(useCase.handle(any())).thenThrow(new UnknownSocialProviderException("MICROSOFT"));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/social-login-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"providers\":[\"MICROSOFT\"]}"))
        .andExpect(status().isBadRequest());
  }
}
