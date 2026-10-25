package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Assign role popups, per Team and for the "No team" group. Fixture in {@link
 * WorkspaceDashboardControllerTestBase}.
 */
class PlatformWorkspaceAssignRoleControllerTest extends WorkspaceDashboardControllerTestBase {

  @Test
  void showsEveryOrganizationAccountNotAlreadyInThisTeamAsEligible() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
    OrganizationAccountSummary eligible =
        new OrganizationAccountSummary(UUID.randomUUID(), "eligible@example.com");
    OrganizationAccountSummary alreadyInTeam =
        new OrganizationAccountSummary(UUID.randomUUID(), "already@example.com");
    when(accountDirectory.listAccountsForOrganization(organization.id()))
        .thenReturn(List.of(eligible, alreadyInTeam));
    when(listMembers.handle(any()))
        .thenReturn(
            List.of(
                WorkspaceMembership.join(workspace.id(), alreadyInTeam.accountId(), role.id())));

    mockMvc
        .perform(get(teamsPath() + "/" + teamId + "/assign-role"))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("eligibleAccounts", List.of(eligible)))
        .andExpect(model().attribute("groupRoles", List.of(role)));
  }

  // Live UX request, 2026-10-02: one role at a time, workspace-wide — an account already holding a
  // role in a DIFFERENT team (not just one already holding a role in THIS team) is excluded too.
  @Test
  void excludesAnAccountAlreadyHoldingARoleInADifferentTeamAsNotEligible() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
    OrganizationAccountSummary eligible =
        new OrganizationAccountSummary(UUID.randomUUID(), "eligible@example.com");
    OrganizationAccountSummary inAnotherTeam =
        new OrganizationAccountSummary(UUID.randomUUID(), "elsewhere@example.com");
    WorkspaceRole otherTeamRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());
    when(accountDirectory.listAccountsForOrganization(organization.id()))
        .thenReturn(List.of(eligible, inAnotherTeam));
    when(listMembers.handle(any()))
        .thenReturn(
            List.of(
                WorkspaceMembership.join(
                    workspace.id(), inAnotherTeam.accountId(), otherTeamRole.id())));

    mockMvc
        .perform(get(teamsPath() + "/" + teamId + "/assign-role"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("eligibleAccounts", List.of(eligible)));
  }

  @Test
  void assignRoleFormForAnUnknownTeamReturnsNotFound() throws Exception {
    mockMvc
        .perform(get(teamsPath() + "/" + UUID.randomUUID() + "/assign-role"))
        .andExpect(status().isNotFound());
  }

  @Test
  void plainAssignRoleForTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", accountId.toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id() + "/teams"));

    verify(assignRoleToAccount).handle(any());
  }

  @Test
  void htmxAssignRoleForTeamPostFiresTheRoleAssignedTriggerOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Trigger", "workspace-role-assigned"))
        .andExpect(
            view()
                .name("organization/platform/fragments/team-assign-role-form :: assignRoleSaved"));
  }

  // Defense in depth: the popup's own Role <select> only ever offers this team's own roles.
  @Test
  void assignRoleForTeamWithARoleNotBelongingToThatTeamReturnsBadRequest() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of());
    WorkspaceRole otherRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", otherRole.id().toString()))
        .andExpect(status().isBadRequest());

    verify(assignRoleToAccount, never()).handle(any());
  }

  // Live UX request, 2026-10-02: defense in depth — the popup's own User <select> already
  // excludes an account like this one, so reaching this normally means a stale form (someone else
  // assigned the account a role elsewhere while the popup was still open), not tampering; re-
  // rendered as a form error rather than a raw 400.
  @Test
  void assignRoleForTeamPostRejectsAnAccountThatAlreadyHasARoleElsewhereInThisWorkspace()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
    UUID accountId = UUID.randomUUID();
    WorkspaceRole otherRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());
    when(listMembers.handle(any()))
        .thenReturn(List.of(WorkspaceMembership.join(workspace.id(), accountId, otherRole.id())));

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", accountId.toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("accountAlreadyHasRoleError", true));

    verify(assignRoleToAccount, never()).handle(any());
  }

  // SDE-III addition, 2026-09-27: the synthetic "No team" group's own "Assign role" popup.
  @Test
  void showsTheAssignRoleFormForTheNoTeamGroup() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/assign-role"))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("groupRoles", List.of(role)));
  }

  @Test
  void plainAssignRoleForNoTeamPostRedirectsOnSuccess() throws Exception {
    mockMvc
        .perform(
            post(rolesPath() + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id() + "/teams"));

    verify(assignRoleToAccount).handle(any());
  }

  @Test
  void assignRoleForNoTeamWithARoleThatIsGroupedIntoATeamReturnsBadRequest() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listGroupedRoleIds.handle(any())).thenReturn(Set.of(role.id()));

    mockMvc
        .perform(
            post(rolesPath() + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isBadRequest());

    verify(assignRoleToAccount, never()).handle(any());
  }

  // Anti-cross-tenant: AssignWorkspaceRoleToAccountService's own accountId re-verification.
  @Test
  void assignRoleRendersNotFoundWhenTheAccountDoesNotBelongToThisOrganization() throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new AccountNotInOrganizationException(accountId, organization.id()))
        .when(assignRoleToAccount)
        .handle(any());

    mockMvc
        .perform(
            post(rolesPath() + "/assign-role")
                .param("accountId", accountId.toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isNotFound());
  }

  // ADR-0028: the Workspace-detail page's own Teams & Roles section.
}
