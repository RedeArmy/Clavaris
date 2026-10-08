package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same rationale as every sibling policy controller test's own Javadoc.
 */
class SetSessionPolicyControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);
  private static final String VALID_BODY =
      "{\"maximumLifetimeMinutes\":10080,\"inactivityTimeoutMinutes\":10080,"
          + "\"reverificationWindowMinutes\":10,\"multiSessionHandlingEnabled\":true}";

  private SetSessionPolicyForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(SetSessionPolicyForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new SetSessionPolicyController(useCase)).build();
  }

  @Test
  void returns200WithTheUpdatedPolicy() throws Exception {
    UUID organizationId = UUID.randomUUID();
    SessionPolicy policy = SessionPolicy.define(organizationId, 10_080, 10_080, 10, true);
    when(useCase.handle(any())).thenReturn(new SetSessionPolicyForOrganizationResult(policy));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + organizationId + "/session-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
        .andExpect(jsonPath("$.maximumLifetimeMinutes").value(10_080))
        .andExpect(jsonPath("$.multiSessionHandlingEnabled").value(true));
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/session-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsAReverificationWindowAboveTenMinutesWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/session-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"maximumLifetimeMinutes\":10080,\"inactivityTimeoutMinutes\":10080,"
                        + "\"reverificationWindowMinutes\":11,\"multiSessionHandlingEnabled\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsAMaximumLifetimeBelowFiveMinutesWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/session-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"maximumLifetimeMinutes\":4,\"inactivityTimeoutMinutes\":10080,"
                        + "\"reverificationWindowMinutes\":10,\"multiSessionHandlingEnabled\":true}"))
        .andExpect(status().isBadRequest());
  }
}
