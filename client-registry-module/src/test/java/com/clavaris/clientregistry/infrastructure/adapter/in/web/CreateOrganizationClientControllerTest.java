package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientResult;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientUseCase;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationNotFoundException;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern as RegisterOAuthClientControllerTest.
 *
 * <p>SDE-III review, 2026-09-15: the 400 tests here are the web-layer half of the scope-escalation
 * fix — {@link OrganizationClient#register}'s own domain-level guard (proved directly in {@code
 * OrganizationClientTest}) is the real, unconditional invariant; this proves an operator minting a
 * Secret Key with an operator-only scope via this REST endpoint gets a clean 400, not {@code
 * GlobalExceptionHandler}'s catch-all 500.
 */
class CreateOrganizationClientControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID organizationId = UUID.randomUUID();
  private CreateOrganizationClientUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(CreateOrganizationClientUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CreateOrganizationClientController(useCase)).build();
  }

  @Test
  void returns201WithTheGeneratedClientIdAndSecret() throws Exception {
    OrganizationClient client =
        OrganizationClient.register(
            organizationId,
            "sk_live_abc",
            "argon2id$hashed",
            List.of(PlatformScopes.WORKSPACES_WRITE));
    when(useCase.handle(any()))
        .thenReturn(new CreateOrganizationClientResult(client, "the-raw-secret"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/secret-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedScopes\":[\"" + PlatformScopes.WORKSPACES_WRITE + "\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.clientId").value("sk_live_abc"))
        .andExpect(jsonPath("$.clientSecret").value("the-raw-secret"));
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(organizationId));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/secret-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedScopes\":[\"" + PlatformScopes.WORKSPACES_WRITE + "\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns400WhenTheUseCaseRejectsAnOperatorOnlyScope() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(
            new IllegalArgumentException(
                "allowedScopes contains an operator-only scope not permitted on an"
                    + " OrganizationClient: "
                    + PlatformScopes.RATE_LIMIT_POLICY_WRITE));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/secret-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedScopes\":[\"" + PlatformScopes.RATE_LIMIT_POLICY_WRITE + "\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns400WhenTheUseCaseRejectsAnUnknownScope() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new IllegalArgumentException("allowedScopes contains an unknown scope: bogus"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/secret-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedScopes\":[\"bogus\"]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsEmptyAllowedScopesWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/secret-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedScopes\":[]}")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isBadRequest());
  }
}
