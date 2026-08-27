package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChangeWorkspaceMemberRoleControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private ChangeWorkspaceMemberRoleUseCase useCase;
  private MockMvc mockMvc;
  private UUID workspaceId;
  private UUID accountId;

  @BeforeEach
  void setUp() {
    useCase = mock(ChangeWorkspaceMemberRoleUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ChangeWorkspaceMemberRoleController(useCase)).build();
    workspaceId = UUID.randomUUID();
    accountId = UUID.randomUUID();
  }

  private String path() {
    return "/api/v1/admin/workspaces/" + workspaceId + "/members/" + accountId + "/role";
  }

  @Test
  void returns200WithTheUpdatedMembership() throws Exception {
    WorkspaceMembership updated =
        WorkspaceMembership.join(workspaceId, accountId, WorkspaceRole.ADMIN);
    when(useCase.handle(any())).thenReturn(updated);

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));
  }

  @Test
  void returns404WhenNoMembershipExists() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new WorkspaceMembershipNotFoundException(workspaceId, accountId));

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenThisWouldDemoteTheLastAdmin() throws Exception {
    when(useCase.handle(any())).thenThrow(new CannotDemoteLastAdminException(workspaceId));

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MEMBER\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsAMissingRoleWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(put(path()).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }
}
