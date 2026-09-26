package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.domain.model.WorkspaceMembership;
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
    UUID roleId = UUID.randomUUID();
    WorkspaceMembership updated = WorkspaceMembership.join(workspaceId, accountId, roleId);
    when(useCase.handle(any())).thenReturn(updated);

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roleId").value(roleId.toString()));
  }

  // ADR-0027 §5: an absent/null roleId is an explicitly allowed "unassign" request, not a
  // validation error.
  @Test
  void anAbsentRoleIdUnassignsTheMembersRole() throws Exception {
    WorkspaceMembership updated = WorkspaceMembership.join(workspaceId, accountId, null);
    when(useCase.handle(any())).thenReturn(updated);

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()));
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
                .content("{\"roleId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns404WhenTheRoleIdDoesNotExist() throws Exception {
    UUID unknownRoleId = UUID.randomUUID();
    when(useCase.handle(any())).thenThrow(new WorkspaceRoleNotFoundException(unknownRoleId));

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + unknownRoleId + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenThisWouldLeaveZeroManageMembersHolders() throws Exception {
    when(useCase.handle(any())).thenThrow(new CannotDemoteLastAdminException(workspaceId));

    mockMvc
        .perform(
            put(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());
  }
}
