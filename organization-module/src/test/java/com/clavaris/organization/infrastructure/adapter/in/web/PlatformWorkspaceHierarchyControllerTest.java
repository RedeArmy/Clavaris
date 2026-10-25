package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The Team -> Role -> Members tab and its Remove action. Fixture in {@link
 * WorkspaceDashboardControllerTestBase}.
 */
class PlatformWorkspaceHierarchyControllerTest extends WorkspaceDashboardControllerTestBase {

  @Test
  void showsTheTeamsHierarchyPage() throws Exception {
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"))
        .andExpect(model().attribute("teams", List.of(team)));
  }

  // Live UX request, 2026-10-02: team-level search/pagination markup (teams-hierarchy-filter.js)
  // and each role as a <details>/<summary> disclosure carrying its own member count.
  @Test
  void rendersTeamSearchMarkupAndEachRolesOwnMemberCount() throws Exception {
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-teams-hierarchy")))
        .andExpect(content().string(containsString("data-teams-search")))
        .andExpect(content().string(containsString("data-team-row")))
        .andExpect(content().string(containsString("data-team-name=\"QA\"")))
        .andExpect(content().string(containsString(role.name() + " (0)")));
  }

  // Live UX request, 2026-09-28: a bare accountId used to render here — real gap, closed via
  // OrganizationAccountDirectory (same cross-module port the "Assign role" picker already uses).
  @Test
  void showsEveryMemberHoldingARoleInTheHierarchyByNameNotId() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())),
                null,
                null,
                false,
                false));
    when(accountDirectory.listAccountsByIds(any(), any()))
        .thenReturn(List.of(new OrganizationAccountSummary(accountId, "Jane Doe")));

    // The raw accountId still legitimately appears in the "Remove" button's own form action URL
    // (live UX request, 2026-09-28) — only the DISPLAYED label is asserted here, not "the id
    // appears nowhere on the page at all."
    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Jane Doe")));
  }

  // Live UX request, 2026-10-02: "Remove" used to be a bare single-click submit for every member
  // except the one edge case already gated behind a confirm — now every member's own Remove opens
  // a real confirm popup first, same TD-FUT-035 shape Delete team/Delete role already use.
  @Test
  void rendersAConfirmPopupForEveryMembersOwnRemoveButton() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())),
                null,
                null,
                false,
                false));
    when(accountDirectory.listAccountsByIds(any(), any()))
        .thenReturn(List.of(new OrganizationAccountSummary(accountId, "Jane Doe")));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(containsString("data-dialog-open=\"remove-member-dialog-" + accountId)))
        .andExpect(
            content().string(containsString("id=\"remove-member-dialog-" + accountId + "\"")));
  }

  // Defensive only — every real accountId here comes from a WorkspaceMembership, which is only
  // ever created against an account the directory already returned
  // (AccountNotInOrganizationException
  // guards that at creation time). Covers the fallback itself, not a reachable production gap.
  @Test
  void fallsBackToTheRawAccountIdWhenItIsMissingFromTheDirectory() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())),
                null,
                null,
                false,
                false));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(accountId.toString())));
  }

  // ADR-0027 §5: a membership can be roleless — that account must not blow up the hierarchy's own
  // per-role grouping (its accountId simply never appears under any role).
  @Test
  void doesNotBlowUpOnARolelessMembership() throws Exception {
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), null)),
                null,
                null,
                false,
                false));

    mockMvc.perform(get(teamsPath())).andExpect(status().isOk());
  }

  // TD-PERF-027: the pagination nav must actually render (and only) when there's a real next/
  // previous page — proves the fix isn't cosmetic (a membersPage model attribute nobody reads).
  @Test
  void rendersTheNextLinkWhenAFurtherPageOfMembersExists() throws Exception {
    KeysetCursor cursor = new KeysetCursor(Instant.now(), UUID.randomUUID());
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), role.id())),
                cursor,
                cursor,
                true,
                false));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("nav class=\"clavaris-pagination\"")))
        .andExpect(content().string(containsString(">Next<")))
        .andExpect(content().string(not(containsString(">Previous<"))));
  }

  @Test
  void rendersNoPaginationNavWhenEverythingFitsOnOnePage() throws Exception {
    when(listMembersPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(), null, null, false, false));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("nav class=\"clavaris-pagination\""))));
  }

  @Test
  void groupsAnUngroupedRoleUnderWithoutTeamInTheHierarchy() throws Exception {
    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("ungroupedRoles", List.of(role)))
        .andExpect(content().string(containsString("No team")));
  }

  @Test
  void teamsHierarchyReturnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization()
      throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(teamsPath())).andExpect(status().isNotFound());
  }

  @Test
  void htmxGetOnTheTeamsHierarchyReturnsJustItsOwnFragment() throws Exception {
    mockMvc
        .perform(get(teamsPath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy :: hierarchy"));
  }

  // Live UX request, 2026-09-28: "Remove" next to each member in the Teams hierarchy.
  @Test
  void plainUnassignMemberFromRolePostRendersTheHierarchyPage() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"));

    verify(changeMemberRole).handle(any());
  }

  @Test
  void htmxUnassignMemberFromRolePostReturnsTheHierarchyFragment() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy :: hierarchy"));

    verify(changeMemberRole).handle(any());
  }

  @Test
  void unassignMemberFromRoleReturnsNotFoundForAnUnknownMembership() throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new WorkspaceMembershipNotFoundException(workspace.id(), accountId))
        .when(changeMemberRole)
        .handle(any());

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unassignMemberFromRoleRendersAnErrorWhenItWouldLeaveNoManageMembersHolder()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new CannotDemoteLastAdminException(workspace.id()))
        .when(changeMemberRole)
        .handle(any());

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true))
        .andExpect(model().attribute("forceConfirmAccountId", accountId));
  }

  // Live UX request, 2026-09-29: typing "unassign" to confirm bypasses ManageMembersGuard —
  // the Clavaris platform dashboard is a higher trust tier than that guard is meant to protect.
  @Test
  void unassignMemberFromRoleWithConfirmedActionForcesTheChange() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role")
                .param("confirmedAction", "unassign"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"));

    ArgumentCaptor<ChangeWorkspaceMemberRoleCommand> captured =
        ArgumentCaptor.forClass(ChangeWorkspaceMemberRoleCommand.class);
    verify(changeMemberRole).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }

  @Test
  void unassignMemberFromRoleReturnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization()
      throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + UUID.randomUUID()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isNotFound());

    verify(changeMemberRole, never()).handle(any());
  }

  // SDE-III addition, 2026-09-27: "Assign role" popup scoped to one specific team (Way 1 of the
  // live UX request — the Teams tab's own per-team/per-"No team" trigger).
}
