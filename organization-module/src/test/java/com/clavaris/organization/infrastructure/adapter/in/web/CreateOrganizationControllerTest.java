package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationResult;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.createorganization.PlatformAccountNotFoundException;
import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner.ProvisionedSigningKey;
import com.clavaris.organization.domain.model.Organization;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
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

  // TD-SEC-007: the controller now resolves an actor from the request's own Authentication —
  // Spring MVC's built-in PrincipalMethodArgumentResolver only succeeds if the (standalone, no
  // real Spring Security filter chain) MockHttpServletRequest actually carries one.
  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private CreateOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(CreateOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new CreateOrganizationController(useCase)).build();
  }

  @Test
  void returns201WithTheCreatedOrganizationAndItsProvisionedSigningKey() throws Exception {
    UUID ownerPlatformAccountId = UUID.randomUUID();
    Organization organization = Organization.register("JobSeeker", ownerPlatformAccountId);
    ProvisionedSigningKey signingKey =
        new ProvisionedSigningKey(UUID.randomUUID(), "a-kid", "RS256");
    when(useCase.handle(any())).thenReturn(new CreateOrganizationResult(organization, signingKey));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"JobSeeker\",\"ownerPlatformAccountId\":\""
                        + ownerPlatformAccountId
                        + "\"}"))
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
                .content(
                    "{\"name\":\"\",\"ownerPlatformAccountId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: an over-length
  // name must be rejected by Bean Validation (400), never reach the use case and risk an unhandled
  // DataIntegrityViolationException (a raw 500) at the DB's own varchar(255) boundary.
  @Test
  void rejectsANameLongerThan255CharactersWithoutEverCallingTheUseCase() throws Exception {
    String tooLong = "a".repeat(256);

    mockMvc
        .perform(
            post("/api/v1/admin/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\""
                        + tooLong
                        + "\",\"ownerPlatformAccountId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsAMissingOwnerPlatformAccountIdWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"JobSeeker\"}"))
        .andExpect(status().isBadRequest());
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: the controller's
  // own half of CreateOrganizationService's new existence check — a well-formed request naming a
  // PlatformAccount that doesn't exist must map to 404, not a raw 500 or a silently-created row.
  @Test
  void returns404WhenTheOwnerPlatformAccountDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new PlatformAccountNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"JobSeeker\",\"ownerPlatformAccountId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isNotFound());
  }
}
