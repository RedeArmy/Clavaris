package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Workspaces: create, open and delete a Workspace. Fixture in {@link
 * WorkspaceDashboardControllerTestBase}.
 */
class PlatformWorkspaceControllerTest extends WorkspaceDashboardControllerTestBase {

  @Test
  void plainCreatePostRedirectsAfterCreatingAWorkspace() throws Exception {
    when(createWorkspace.handle(any())).thenReturn(workspace);

    mockMvc
        .perform(post(workspacesPath()).param("name", "Engineering"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + organization.id()));

    verify(createWorkspace).handle(any());
  }

  @Test
  void createPostWithNoNameReRendersTheOrganizationPageWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post(workspacesPath()).param("name", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"));

    verify(createWorkspace, never()).handle(any());
  }

  @Test
  void htmxCreatePostReturnsTheWorkspacesFragment() throws Exception {
    when(createWorkspace.handle(any())).thenReturn(workspace);

    mockMvc
        .perform(post(workspacesPath()).param("name", "Engineering").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"));
  }

  @Test
  void showsTheWorkspaceAndItsTeams() throws Exception {
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));

    mockMvc
        .perform(get(workspacesPath() + "/" + workspace.id()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("workspace", workspace))
        .andExpect(model().attribute("teams", List.of(team)));
  }

  @Test
  void showsABackLinkToTheOrganizationsWorkspacesTab() throws Exception {
    String organizationDetailPath = "/platform/dashboard/organizations/" + organization.id();

    mockMvc
        .perform(get(workspacesPath() + "/" + workspace.id()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("clavaris-back-button")))
        .andExpect(content().string(containsString("href=\"" + organizationDetailPath + "\"")));
  }

  @Test
  void returnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization() throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get(workspacesPath() + "/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(workspacesPath() + "/" + workspace.id())).andExpect(status().isNotFound());
  }

  // Live UX request, 2026-09-28 (revised same day): "Delete" next to "View" on the Workspaces
  // list, gated behind a popup requiring the Workspace's own current name to be typed in.
  @Test
  void plainDeleteWorkspacePostRedirectsOnSuccessWhenTheTypedNameMatches() throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", workspace.name()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + organization.id()));

    verify(deleteWorkspace).handle(any());
  }

  @Test
  void htmxDeleteWorkspacePostReturnsTheWorkspacesFragmentWhenTheTypedNameMatches()
      throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", workspace.name())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"));

    verify(deleteWorkspace).handle(any());
  }

  @Test
  void plainDeleteWorkspacePostWithTheWrongTypedNameReRendersWithAMismatchErrorInsteadOfDeleting()
      throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", "not the real name"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"))
        .andExpect(model().attribute("deleteWorkspaceMismatchId", workspace.id()));

    verify(deleteWorkspace, never()).handle(any());
  }

  @Test
  void htmxDeleteWorkspacePostWithTheWrongTypedNameReturnsTheFragmentWithAMismatchError()
      throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", "not the real name")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"))
        .andExpect(model().attribute("deleteWorkspaceMismatchId", workspace.id()));

    verify(deleteWorkspace, never()).handle(any());
  }

  @Test
  void deleteWorkspaceReturnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization()
      throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post(workspacesPath() + "/" + UUID.randomUUID() + "/delete")
                .param("confirmedName", "anything"))
        .andExpect(status().isNotFound());

    verify(deleteWorkspace, never()).handle(any());
  }

  // SDE-III addition, 2026-09-27: the Teams tab's own read-only hierarchy.
}
