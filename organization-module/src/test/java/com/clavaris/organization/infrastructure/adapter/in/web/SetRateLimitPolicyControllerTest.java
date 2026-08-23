package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as CreateOrganizationControllerTest's own Javadoc. */
class SetRateLimitPolicyControllerTest {

  // TD-SEC-007: see CreateOrganizationControllerTest's own identical field for why this is
  // needed now that the controller resolves an actor from the request's Authentication.
  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private SetRateLimitPolicyForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(SetRateLimitPolicyForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new SetRateLimitPolicyController(useCase)).build();
  }

  @Test
  void returns200WithTheUpdatedPolicy() throws Exception {
    UUID organizationId = UUID.randomUUID();
    RateLimitPolicy policy = RateLimitPolicy.define(organizationId, 500, 6000);
    when(useCase.handle(any())).thenReturn(new SetRateLimitPolicyForOrganizationResult(policy));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + organizationId + "/rate-limit-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestsPerMinute\":500}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
        .andExpect(jsonPath("$.requestsPerMinute").value(500));
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/rate-limit-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestsPerMinute\":500}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns400WhenTheValueExceedsTheHardSystemWideCap() throws Exception {
    when(useCase.handle(any())).thenThrow(new IllegalArgumentException("exceeds hard cap"));

    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/rate-limit-policy")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestsPerMinute\":999999}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsANonPositiveRequestsPerMinuteWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/organizations/" + UUID.randomUUID() + "/rate-limit-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestsPerMinute\":0}"))
        .andExpect(status().isBadRequest());
  }
}
