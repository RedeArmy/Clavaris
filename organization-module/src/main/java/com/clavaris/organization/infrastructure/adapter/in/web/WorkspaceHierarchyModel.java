package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedUseCase;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * ADR-0028: the Teams tab's read-only hierarchy — the same teams/roles as the Manage tab ({@link
 * WorkspaceTeamsModel}, shared, not duplicated) plus every member currently holding each role,
 * keyed by roleId. Every roleId the Organization has gets a (possibly empty) entry — never a
 * missing map key — so the template never null-checks {@code accountIdsByRoleId.get(...)}.
 */
@Component
final class WorkspaceHierarchyModel {

  private final WorkspaceTeamsModel teamsModel;
  private final ListWorkspaceMembersPagedUseCase listMembersPagedUseCase;
  private final OrganizationAccountDirectory accountDirectory;

  /* default */ WorkspaceHierarchyModel(
      final WorkspaceTeamsModel teamsModel,
      final ListWorkspaceMembersPagedUseCase listMembersPagedUseCase,
      final OrganizationAccountDirectory accountDirectory) {
    this.teamsModel = teamsModel;
    this.listMembersPagedUseCase = listMembersPagedUseCase;
    this.accountDirectory = accountDirectory;
  }

  // SDE-III addition, 2026-09-27: the Teams tab's own read-only hierarchy — same
  // teams/rolesByTeamId
  // /ungroupedRoles as the Manage tab (loadTeamsAndRoles is shared, not duplicated), plus every
  // member currently holding each role, keyed by roleId. Every roleId this Organization has gets a
  // (possibly empty) entry — never a missing map key — so the template never has to null-check
  // accountIdsByRoleId.get(...) the way it would if Collectors.groupingBy's own "only present keys
  // that had at least one match" behavior were used directly.
  // TD-PERF-027 (fixed): this page used to read every membership in the Workspace (unbounded) and
  // then resolve labels for every account in the whole ORGANIZATION (unbounded across a much wider
  // scope than this one Workspace) on every single request. Now reads one keyset page of
  // memberships and resolves labels only for the accountIds actually rendered on this page.
  /* default */ void populateTeamsHierarchyModel(
      final Model model, final Workspace workspace, final KeysetPageRequest pageRequest) {
    model.addAttribute("workspace", workspace);
    final WorkspaceTeamsModel.TeamsAndRoles teamsAndRoles = teamsModel.loadTeamsAndRoles(workspace);

    final Map<UUID, List<UUID>> accountIdsByRoleId = new LinkedHashMap<>();
    teamsAndRoles.rolesByTeamId().values().stream()
        .flatMap(List::stream)
        .forEach(role -> accountIdsByRoleId.put(role.id(), new ArrayList<>()));
    teamsAndRoles
        .ungroupedRoles()
        .forEach(role -> accountIdsByRoleId.put(role.id(), new ArrayList<>()));
    final KeysetPage<WorkspaceMembership> membersPage =
        listMembersPagedUseCase.handle(
            new ListWorkspaceMembersPagedQuery(workspace.id(), pageRequest));
    membersPage.content().stream()
        .filter(membership -> membership.roleId() != null)
        .forEach(
            membership ->
                accountIdsByRoleId
                    .computeIfAbsent(membership.roleId(), key -> new ArrayList<>())
                    .add(membership.accountId()));

    model.addAttribute("teams", teamsAndRoles.teams());
    model.addAttribute("teamRoles", teamsAndRoles.rolesByTeamId());
    model.addAttribute("ungroupedRoles", teamsAndRoles.ungroupedRoles());
    model.addAttribute("accountIdsByRoleId", accountIdsByRoleId);
    model.addAttribute("membersPage", membersPage);
    // Live UX request, 2026-09-28: the member list used to render a bare accountId — see
    // OrganizationAccountDirectoryBridge's own Javadoc for why the label (name, or email as a
    // fallback) can only be resolved through this cross-module port, never a local join.
    // TD-PERF-027: scoped to just this page's own accountIds — the old whole-Organization
    // accountLabelById helper this replaced had no other caller and was removed.
    final Set<UUID> renderedAccountIds =
        membersPage.content().stream()
            .map(WorkspaceMembership::accountId)
            .collect(Collectors.toSet());
    model.addAttribute(
        "accountLabelById",
        accountDirectory.listAccountsByIds(workspace.organizationId(), renderedAccountIds).stream()
            .collect(
                Collectors.toMap(
                    OrganizationAccountSummary::accountId, OrganizationAccountSummary::label)));
  }
}
