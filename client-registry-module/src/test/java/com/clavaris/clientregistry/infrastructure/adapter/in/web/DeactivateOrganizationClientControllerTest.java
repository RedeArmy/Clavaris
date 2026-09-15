package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.deactivateorganizationclient.DeactivateOrganizationClientUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern as RegisterOAuthClientControllerTest.
 *
 * <p>SDE-III review, 2026-09-15: the 409 test is the web-layer half of the optimistic-locking fix —
 * {@code OrganizationClient}'s own {@code @Version}-backed conflict (proved directly against real
 * Postgres in {@code JpaOrganizationClientRepositoryTest}) is the real, unconditional guarantee;
 * this proves it surfaces as a clean 409, not an unhandled 500.
 */
class DeactivateOrganizationClientControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private DeactivateOrganizationClientUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(DeactivateOrganizationClientUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DeactivateOrganizationClientController(useCase))
            .build();
  }

  @Test
  void returns204OnSuccess() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_live_abc/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns404WhenTheClientDoesNotExist() throws Exception {
    doThrow(new OrganizationClientNotFoundException("sk_live_abc")).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_live_abc/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheClientWasModifiedConcurrently() throws Exception {
    doThrow(new ConcurrentClientModificationException("sk_live_abc")).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_live_abc/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
