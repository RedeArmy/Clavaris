package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersQuery;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ui.Model;

/**
 * TD-PERF-042: who the dashboard's "Assign role" picker offers. It used to hold every account of
 * the Organization, paged into memory and rendered as one {@code <select>}. It now holds the first
 * {@value #ELIGIBLE_ACCOUNTS_SHOWN} eligible accounts matching what the operator typed (the newest
 * accounts when nothing was typed), found by asking the directory for one bounded page, dropping
 * the accounts that already hold a role in this Workspace (the workflow's one-role-at-a-time rule,
 * see {@link PlatformWorkspaceAssignRoleController}) and sorting by label. The page asked for is
 * wider than the list shown, so a Workspace whose members mostly hold roles still has candidates to
 * offer; typing narrows it further. {@code accountsTruncated} tells the form there are more.
 */
final class AssignRoleCandidates {

  /* package */ static final int ELIGIBLE_ACCOUNTS_SHOWN = 25;

  private final OrganizationAccountDirectory accountDirectory;
  private final ListWorkspaceMembersUseCase listMembers;

  /* package */ AssignRoleCandidates(
      final OrganizationAccountDirectory accountDirectory,
      final ListWorkspaceMembersUseCase listMembers) {
    this.accountDirectory = accountDirectory;
    this.listMembers = listMembers;
  }

  /* package */ void addEligibleAccounts(
      final Model model,
      final UUID organizationId,
      final Workspace workspace,
      final String searchTerm) {
    final Set<UUID> holdingARole = accountsWithAnyRole(workspace);
    final List<OrganizationAccountSummary> eligible =
        accountDirectory
            .searchAccounts(organizationId, searchTerm, KeysetPageRequest.MAX_SIZE)
            .stream()
            .filter(account -> !holdingARole.contains(account.accountId()))
            .sorted(Comparator.comparing(OrganizationAccountSummary::label))
            .toList();
    model.addAttribute(
        "eligibleAccounts", eligible.stream().limit(ELIGIBLE_ACCOUNTS_SHOWN).toList());
    model.addAttribute("accountsTruncated", eligible.size() > ELIGIBLE_ACCOUNTS_SHOWN);
    model.addAttribute("accountSearchTerm", searchTerm == null ? "" : searchTerm);
  }

  /* package */ Set<UUID> accountsWithAnyRole(final Workspace workspace) {
    return listMembers.handle(new ListWorkspaceMembersQuery(workspace.id())).stream()
        .filter(membership -> membership.roleId() != null)
        .map(WorkspaceMembership::accountId)
        .collect(Collectors.toSet());
  }
}
