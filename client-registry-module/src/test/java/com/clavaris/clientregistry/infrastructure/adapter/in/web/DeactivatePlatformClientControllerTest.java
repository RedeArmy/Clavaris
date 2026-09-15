package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateplatformclient.DeactivatePlatformClientUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern as RegisterOAuthClientControllerTest. See
 * DeactivateOrganizationClientControllerTest's own Javadoc for the 409 test's rationale — the
 * highest-value credential in the system is the one this race matters most for.
 */
class DeactivatePlatformClientControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private DeactivatePlatformClientUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(DeactivatePlatformClientUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DeactivatePlatformClientController(useCase)).build();
  }

  @Test
  void returns204OnSuccess() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/platform-clients/bootstrap-client/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns404WhenTheClientDoesNotExist() throws Exception {
    doThrow(new PlatformClientNotFoundException("bootstrap-client")).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/platform-clients/bootstrap-client/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheClientWasModifiedConcurrently() throws Exception {
    doThrow(new ConcurrentClientModificationException("bootstrap-client"))
        .when(useCase)
        .handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/platform-clients/bootstrap-client/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
