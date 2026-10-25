package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.addroletoworkspaceteam.WorkspaceRoleAlreadyInAnotherTeamException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamCommand;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Teams: create, rename, delete, and add roles to a Team. Fixture in {@link
 * WorkspaceDashboardControllerTestBase}.
 */
class PlatformWorkspaceTeamControllerTest extends WorkspaceDashboardControllerTestBase {

  @Test
  void plainCreateTeamPostRedirectsOnSuccess() throws Exception {
    mockMvc
        .perform(post(teamsPath()).param("name", "QA"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(createTeam).handle(any());
  }

  @Test
  void htmxCreateTeamPostReturnsTheTeamsFragment() throws Exception {
    mockMvc
        .perform(post(teamsPath()).param("name", "QA").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail :: teams"));
  }

  @Test
  void createTeamWithADuplicateNameRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    doThrow(new DuplicateWorkspaceTeamNameException("QA")).when(createTeam).handle(any());

    mockMvc
        .perform(post(teamsPath()).param("name", "QA"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("duplicateTeamNameError", true));
  }

  @Test
  void plainRenameTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/rename").param("name", "Quality Assurance"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(renameTeam).handle(any());
  }

  @Test
  void plainDeleteTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(deleteTeam).handle(any());
  }

  // Live UX request, 2026-09-29: unlike deleteRole above, this aborts the WHOLE team deletion
  // (DeleteWorkspaceTeamService's own Javadoc explains why) — same "type UNASSIGN to confirm"
  // popup, this time retrying the entire delete, not just one role's own unassign.
  @Test
  void deleteTeamThatWouldLeaveNoManageMembersHolderRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new CannotDemoteLastAdminException(workspace.id())).when(deleteTeam).handle(any());

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true))
        .andExpect(model().attribute("forceConfirmTeamId", teamId));
  }

  @Test
  void deleteTeamWithConfirmedActionForcesTheDeletion() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/delete").param("confirmedAction", "unassign"))
        .andExpect(status().is3xxRedirection());

    ArgumentCaptor<DeleteWorkspaceTeamCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceTeamCommand.class);
    verify(deleteTeam).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }

  @Test
  void plainAddRoleToTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles").param("roleIds", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam).handle(any());
  }

  // Live UX request, 2026-10-01: the picker popup is multi-select — one submit carries several
  // roleIds, each added through its own AddRoleToWorkspaceTeamUseCase#handle call.
  @Test
  void addRoleToTeamPostWithMultipleRoleIdsHandlesEachOneIndividually() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceRole secondRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/roles")
                .param("roleIds", role.id().toString(), secondRole.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam, times(2)).handle(any());
  }

  @Test
  void addRoleToTeamPostWithNoRoleIdsSelectedIsANoOp() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void addRoleToTeamAlreadyInAnotherTeamRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new WorkspaceRoleAlreadyInAnotherTeamException(role.id(), UUID.randomUUID()))
        .when(addRoleToTeam)
        .handle(any());

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles").param("roleIds", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("roleAlreadyInAnotherTeamError", true));
  }

  // SDE-III redesign, 2026-09-27: "Create a new role" popup — composes CreateWorkspaceRoleUseCase
  // with AddRoleToWorkspaceTeamUseCase in one submit.
}
