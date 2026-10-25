package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Creating and deleting a role from the Workspace-detail page. Fixture in {@link
 * WorkspaceDashboardControllerTestBase}.
 */
class PlatformWorkspaceTeamRoleControllerTest extends WorkspaceDashboardControllerTestBase {

  @Test
  void plainCreateRolePostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceRole created = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(createRole.handle(any())).thenReturn(created);

    mockMvc
        .perform(post(rolesPath()).param("name", "Reviewer").param("teamId", teamId.toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(createRole).handle(any());
    verify(addRoleToTeam).handle(any());
  }

  // Live UX request, 2026-09-28: "No team" is now a first-class choice in the popup itself,
  // not just something a role ends up in after its team is later deleted.
  @Test
  void plainCreateRolePostWithoutATeamCreatesAnUngroupedRole() throws Exception {
    WorkspaceRole created = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(createRole.handle(any())).thenReturn(created);

    mockMvc
        .perform(post(rolesPath()).param("name", "Reviewer").param("teamId", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(createRole).handle(any());
    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void htmxCreateRolePostReturnsTheTeamsFragment() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceRole created = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(createRole.handle(any())).thenReturn(created);

    mockMvc
        .perform(
            post(rolesPath())
                .param("name", "Reviewer")
                .param("teamId", teamId.toString())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail :: teams"));
  }

  @Test
  void createRoleWithNoNameReRendersThePageWithoutCreatingAnything() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(rolesPath()).param("name", "").param("teamId", teamId.toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"));

    verify(createRole, never()).handle(any());
    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void createRoleWithADuplicateNameRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new DuplicateWorkspaceRoleNameException("Reviewer")).when(createRole).handle(any());

    mockMvc
        .perform(post(rolesPath()).param("name", "Reviewer").param("teamId", teamId.toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("duplicateRoleNameError", true));

    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void plainDeleteRolePostRedirectsOnSuccess() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(deleteRole).handle(any());
  }

  @Test
  void deleteRoleNotBelongingToThisOrganizationReturnsNotFound() throws Exception {
    mockMvc
        .perform(post(rolesPath() + "/" + UUID.randomUUID() + "/delete"))
        .andExpect(status().isNotFound());

    verify(deleteRole, never()).handle(any());
  }

  @Test
  void deleteRoleStillAssignedRendersAnErrorInsteadOfPropagatingTheException() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));
    doThrow(new WorkspaceRoleStillAssignedException(plainRole.id())).when(deleteRole).handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("roleStillAssignedError", true));
  }

  @Test
  void deleteRoleWithChildRolesRendersAnErrorInsteadOfPropagatingTheException() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));
    doThrow(new WorkspaceRoleHasChildRolesException(plainRole.id())).when(deleteRole).handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("roleHasChildRolesError", true));
  }

  @Test
  void deleteReservedRoleReturnsConflictInsteadOfPropagatingTheException() throws Exception {
    doThrow(new CannotDeleteReservedWorkspaceRoleException(role.id()))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + role.id() + "/delete"))
        .andExpect(status().isConflict());
  }

  // Live UX request, 2026-09-28: deleting a role now auto-unassigns every holder in this
  // Workspace first — this is that bulk unassign hitting ManageMembersGuard.
  @Test
  void deleteRoleThatWouldLeaveNoManageMembersHolderRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));
    doThrow(new CannotDemoteLastAdminException(workspace.id())).when(deleteRole).handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true))
        .andExpect(model().attribute("forceConfirmRoleId", plainRole.id()));
  }

  // Live UX request, 2026-09-29: typing "unassign" to confirm bypasses ManageMembersGuard —
  // the Clavaris platform dashboard is a higher trust tier than that guard is meant to protect.
  @Test
  void deleteRoleWithConfirmedActionForcesTheDeletion() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));

    mockMvc
        .perform(
            post(rolesPath() + "/" + plainRole.id() + "/delete")
                .param("confirmedAction", "unassign"))
        .andExpect(status().is3xxRedirection());

    ArgumentCaptor<DeleteWorkspaceRoleCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceRoleCommand.class);
    verify(deleteRole).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }
}
