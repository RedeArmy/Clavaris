package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationCommand;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationResult;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationUseCase;
import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.SigningKeyNotFoundException;
import com.clavaris.identity.domain.model.OrganizationId;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as RotateSigningKeyControllerTest. */
class PurgeSigningKeyControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private PurgeSigningKeyForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(PurgeSigningKeyForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new PurgeSigningKeyController(useCase)).build();
  }

  @Test
  void returns200WithThePurgedAndReplacementKid() throws Exception {
    UUID organizationId = UUID.randomUUID();
    when(useCase.handle(any()))
        .thenReturn(
            new PurgeSigningKeyForOrganizationResult(
                new OrganizationId(organizationId), "compromised-kid", "replacement-kid"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + organizationId
                    + "/signing-keys/compromised-kid:purge")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
        .andExpect(jsonPath("$.purgedKid").value("compromised-kid"))
        .andExpect(jsonPath("$.replacementKid").value("replacement-kid"));
  }

  @Test
  void returns404WhenNoSigningKeyExistsWithTheGivenKid() throws Exception {
    UUID organizationId = UUID.randomUUID();
    when(useCase.handle(any()))
        .thenThrow(
            new SigningKeyNotFoundException(new OrganizationId(organizationId), "unknown-kid"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + organizationId
                    + "/signing-keys/unknown-kid:purge")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void theCallingPlatformClientBecomesTheAuditActor() throws Exception {
    UUID organizationId = UUID.randomUUID();
    when(useCase.handle(any()))
        .thenReturn(
            new PurgeSigningKeyForOrganizationResult(
                new OrganizationId(organizationId), "compromised-kid", "replacement-kid"));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/"
                    + organizationId
                    + "/signing-keys/compromised-kid:purge")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isOk());

    ArgumentCaptor<PurgeSigningKeyForOrganizationCommand> captor =
        ArgumentCaptor.forClass(PurgeSigningKeyForOrganizationCommand.class);
    verify(useCase).handle(captor.capture());
    assertThat(captor.getValue().kid()).isEqualTo("compromised-kid");
    assertThat(captor.getValue().actor().id()).isEqualTo("test-platform-client");
  }
}
