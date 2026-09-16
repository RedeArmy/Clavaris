package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;
import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretUseCase;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SDE-III review, 2026-09-15: same rationale as {@code DeactivateOrganizationClientControllerTest}
 * — this platform-tier REST endpoint had no test coverage of its own before this session. Covers
 * both real gaps found the same day: the null-{@code organizationId} pass-through and the
 * {@code @Version}-backed conflict surfacing as a clean 409.
 */
class RotateOrganizationClientSecretControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private RotateOrganizationClientSecretUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RotateOrganizationClientSecretUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new RotateOrganizationClientSecretController(useCase))
            .build();
  }

  @Test
  void rotatePassesANullOrganizationIdAndReturnsTheNewSecret() throws Exception {
    when(useCase.handle(any()))
        .thenReturn(new RotateOrganizationClientSecretResult("sk_test_abc", "new-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_test_abc/rotate-secret")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecret").value("new-raw-secret"));

    verify(useCase)
        .handle(
            argThat(
                command ->
                    "sk_test_abc".equals(command.clientId()) && command.organizationId() == null));
  }

  @Test
  void rotateReturns404WhenTheUseCaseThrowsNotFound() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationClientNotFoundException("sk_test_ghost"));

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_test_ghost/rotate-secret")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void rotateReturns409WhenTheClientWasModifiedConcurrently() throws Exception {
    when(useCase.handle(any())).thenThrow(new ConcurrentClientModificationException("sk_test_abc"));

    mockMvc
        .perform(
            post("/api/v1/admin/organization-clients/sk_test_abc/rotate-secret")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
