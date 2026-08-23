package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.NoActiveSigningKeyException;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationResult;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as organization-module's own controller tests. */
class RotateSigningKeyControllerTest {

  // TD-SEC-007: the controller resolves an actor from the request's own Authentication — see
  // organization-module's CreateOrganizationControllerTest for the identical rationale.
  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private RotateSigningKeyForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RotateSigningKeyForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new RotateSigningKeyController(useCase)).build();
  }

  @Test
  void returns200WithTheNewAndPreviousKid() throws Exception {
    UUID organizationId = UUID.randomUUID();
    OrganizationId orgId = new OrganizationId(organizationId);
    SigningKey newKey = SigningKey.activate(orgId, "new-kid", "RS256");
    when(useCase.handle(any()))
        .thenReturn(new RotateSigningKeyForOrganizationResult(newKey, "old-kid"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/signing-keys/rotate")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
        .andExpect(jsonPath("$.newKid").value("new-kid"))
        .andExpect(jsonPath("$.previousKid").value("old-kid"));
  }

  @Test
  void returns404WhenTheOrganizationHasNoActiveSigningKey() throws Exception {
    UUID organizationId = UUID.randomUUID();
    when(useCase.handle(any()))
        .thenThrow(new NoActiveSigningKeyException(new OrganizationId(organizationId)));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/signing-keys/rotate")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }
}
