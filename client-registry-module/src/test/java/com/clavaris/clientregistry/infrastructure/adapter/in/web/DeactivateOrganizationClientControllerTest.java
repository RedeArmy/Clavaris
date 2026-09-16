package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
 * SDE-III review, 2026-09-15: this platform-tier REST endpoint had no test coverage of its own
 * before this session. Covers both real gaps found the same day: (1) this controller always passes
 * a null {@code organizationId} — see {@code DeactivateOrganizationClientCommand}'s own Javadoc for
 * why that's this endpoint's own deliberate, unscoped reach, not an oversight — and (2) {@code
 * OrganizationClient}'s own {@code @Version}-backed conflict ({@code
 * JpaOrganizationClientRepositoryTest} proves the real, unconditional guarantee directly against
 * Postgres; this proves it surfaces as a clean 409 here, not an unhandled 500).
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
  void revokePassesANullOrganizationIdAndReturns204() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_test_abc/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());

    verify(useCase)
        .handle(
            argThat(
                command ->
                    "sk_test_abc".equals(command.clientId()) && command.organizationId() == null));
  }

  @Test
  void revokeReturns404WhenTheUseCaseThrowsNotFound() throws Exception {
    doThrow(new OrganizationClientNotFoundException("sk_test_ghost")).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_test_ghost/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void revokeReturns409WhenTheClientWasModifiedConcurrently() throws Exception {
    doThrow(new ConcurrentClientModificationException("sk_test_abc")).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_test_abc/revoke")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
