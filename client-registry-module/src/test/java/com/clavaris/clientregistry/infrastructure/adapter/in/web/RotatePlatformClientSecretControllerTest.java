package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretUseCase;
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
class RotatePlatformClientSecretControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private RotatePlatformClientSecretUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RotatePlatformClientSecretUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new RotatePlatformClientSecretController(useCase)).build();
  }

  @Test
  void returns200WithTheNewRawSecret() throws Exception {
    when(useCase.handle(any()))
        .thenReturn(new RotatePlatformClientSecretResult("bootstrap-client", "new-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/platform-clients/bootstrap-client/rotate-secret")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecret").value("new-raw-secret"));
  }

  @Test
  void returns404WhenTheClientDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new PlatformClientNotFoundException("bootstrap-client"));

    mockMvc
        .perform(
            post("/api/v1/admin/platform-clients/bootstrap-client/rotate-secret")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheClientWasModifiedConcurrently() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new ConcurrentClientModificationException("bootstrap-client"));

    mockMvc
        .perform(
            post("/api/v1/admin/platform-clients/bootstrap-client/rotate-secret")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
