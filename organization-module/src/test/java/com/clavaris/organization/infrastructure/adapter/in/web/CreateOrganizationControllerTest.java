package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationResult;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner.ProvisionedSigningKey;
import com.clavaris.organization.domain.model.Organization;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup, not {@code @WebMvcTest}: confirmed live (identity-module's own
 * RegisterAccountControllerTest) that Spring Boot 4.1 no longer has that test slice at all. No view
 * resolver needed here (unlike that Thymeleaf controller) — this is a plain JSON
 * {@code @RestController}, so {@link MockMvcBuilders#standaloneSetup} needs only this one
 * controller.
 */
class CreateOrganizationControllerTest {

  private CreateOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(CreateOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new CreateOrganizationController(useCase)).build();
  }

  @Test
  void returns201WithTheCreatedOrganizationAndItsProvisionedSigningKey() throws Exception {
    Organization organization = Organization.register("JobSeeker");
    ProvisionedSigningKey signingKey =
        new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256");
    when(useCase.handle(any())).thenReturn(new CreateOrganizationResult(organization, signingKey));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"JobSeeker\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(organization.id().toString()))
        .andExpect(jsonPath("$.name").value("JobSeeker"))
        .andExpect(jsonPath("$.signingKey.kid").value("a-kid"))
        .andExpect(jsonPath("$.signingKey.algorithm").value("RS256"));
  }

  @Test
  void rejectsABlankNameWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }
}
