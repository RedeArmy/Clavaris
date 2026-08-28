package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.domain.model.Workspace;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same rationale as {@code CreateOrganizationControllerTest}. */
class CreateWorkspaceControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private CreateWorkspaceUseCase useCase;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    useCase = mock(CreateWorkspaceUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new CreateWorkspaceController(useCase)).build();
    organizationId = UUID.randomUUID();
  }

  @Test
  void returns201WithTheCreatedWorkspace() throws Exception {
    Workspace workspace = Workspace.register(organizationId, "Engineering");
    when(useCase.handle(any())).thenReturn(workspace);

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/workspaces")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Engineering\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(workspace.id().toString()))
        .andExpect(jsonPath("$.name").value("Engineering"));
  }

  @Test
  void rejectsABlankNameWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new OrganizationNotFoundException(organizationId));

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + "/workspaces")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Engineering\"}"))
        .andExpect(status().isNotFound());
  }
}
