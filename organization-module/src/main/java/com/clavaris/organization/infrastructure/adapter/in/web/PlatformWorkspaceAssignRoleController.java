package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.ORGANIZATIONS_REDIRECT_PREFIX;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.WORKSPACES_PATH_SEGMENT;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.isHtmxRequest;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountCommand;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The "Assign role" popups on the Teams tab, one scoped to a Team's own roles and one for the
 * synthetic "No team" group. Distinct from {@link PlatformAccountWorkspaceRoleController}, which
 * assigns from the Users tab and deliberately replaces a role in place; here an account holds one
 * role at a time, workspace-wide, as a workflow restriction of this one entry point (not a domain
 * invariant, so {@code AssignWorkspaceRoleToAccountService} does not enforce it). See {@link
 * PlatformWorkspaceController} for the family this belongs to.
 */
// PMD.LongVariable: collaborator names spell out the use case they hold.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspaces")
public class PlatformWorkspaceAssignRoleController {

  private static final String ASSIGN_ROLE_FORM_FRAGMENT =
      "organization/platform/fragments/team-assign-role-form :: assignRoleForm";
  private static final String ACCOUNT_OPTIONS_FRAGMENT =
      "organization/platform/fragments/team-assign-role-form :: accountOptions";
  private static final String ASSIGN_ROLE_SAVED_FRAGMENT =
      "organization/platform/fragments/team-assign-role-form :: assignRoleSaved";
  // htmx's own response-header convention — same ADR-0029 event name/purpose
  // PlatformAccountWorkspaceRoleController's own identical constant documents: workspace-
  // teams-hierarchy.html's own hierarchy fragment listens for this on document.body to
  // self-refresh after a successful assignment.
  private static final String ROLE_ASSIGNED_EVENT = "workspace-role-assigned";
  // Live UX request, 2026-10-02: Team hierarchy's own "one role at a time, workspace-wide" rule —
  // see populateAssignRoleModel's own comment.
  private static final String ACCOUNT_ALREADY_HAS_A_ROLE_ERROR_ATTRIBUTE =
      "accountAlreadyHasRoleError";

  private final WorkspaceDashboardSupport access;
  private final WorkspaceTeamsModel teamsModel;
  private final AssignRoleCandidates candidates;
  private final AssignWorkspaceRoleToAccountUseCase assignRoleToAccountUseCase;

  public PlatformWorkspaceAssignRoleController(
      final WorkspaceDashboardSupport access,
      final WorkspaceTeamsModel teamsModel,
      final ListWorkspaceMembersUseCase listMembersUseCase,
      final OrganizationAccountDirectory accountDirectory,
      final AssignWorkspaceRoleToAccountUseCase assignRoleToAccountUseCase) {
    this.access = access;
    this.teamsModel = teamsModel;
    this.candidates = new AssignRoleCandidates(accountDirectory, listMembersUseCase);
    this.assignRoleToAccountUseCase = assignRoleToAccountUseCase;
  }

  // Live UX request, 2026-09-27: "Assign role" popup scoped to one specific team — the "User"
  // picker excludes every account that already holds one of THIS team's own roles (an account with
  // a role in a DIFFERENT team, or no role at all, is still offered — assigning them here simply
  // moves/originates their one membership, same single-role model
  // AssignWorkspaceRoleToAccountUseCase's
  // own Javadoc documents).
  @GetMapping("/{workspaceId}/teams/{teamId}/assign-role")
  public String showAssignRoleFormForTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> teamRoles = teamsModel.requireOwnedTeamRoles(workspace, teamId);
    populateAssignRoleModel(
        model,
        organizationId,
        workspace,
        teamRoles,
        assignRoleToTeamAction(organizationId, workspaceId, teamId));
    return ASSIGN_ROLE_FORM_FRAGMENT;
  }

  @PostMapping("/{workspaceId}/teams/{teamId}/assign-role")
  public String assignRoleForTeam(
      final HttpServletRequest request,
      final HttpServletResponse response,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      @RequestParam final UUID accountId,
      @RequestParam final UUID roleId,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> teamRoles = teamsModel.requireOwnedTeamRoles(workspace, teamId);
    return processAssignRole(
        request,
        response,
        new AssignRoleAttempt(
            organizationId,
            workspace,
            accountId,
            roleId,
            teamRoles,
            ownerPlatformAccountId,
            assignRoleToTeamAction(organizationId, workspaceId, teamId)),
        model);
  }

  // Live UX request, 2026-09-27: the synthetic "No team" group's own "Assign role" popup — same
  // shape as the per-team one above, scoped to this Workspace's own ungrouped roles instead of one
  // team's roles. Only reachable when at least one ungrouped role exists — the template never
  // renders this button otherwise, same guard the "No team" card's own th:unless already applies.
  @GetMapping("/{workspaceId}/roles/assign-role")
  public String showAssignRoleFormForNoTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> ungroupedRoles =
        teamsModel.loadTeamsAndRoles(workspace).ungroupedRoles();
    populateAssignRoleModel(
        model,
        organizationId,
        workspace,
        ungroupedRoles,
        assignRoleToNoTeamAction(organizationId, workspaceId));
    return ASSIGN_ROLE_FORM_FRAGMENT;
  }

  @PostMapping("/{workspaceId}/roles/assign-role")
  public String assignRoleForNoTeam(
      final HttpServletRequest request,
      final HttpServletResponse response,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @RequestParam final UUID accountId,
      @RequestParam final UUID roleId,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> ungroupedRoles =
        teamsModel.loadTeamsAndRoles(workspace).ungroupedRoles();
    return processAssignRole(
        request,
        response,
        new AssignRoleAttempt(
            organizationId,
            workspace,
            accountId,
            roleId,
            ungroupedRoles,
            ownerPlatformAccountId,
            assignRoleToNoTeamAction(organizationId, workspaceId)),
        model);
  }

  private static String assignRoleToTeamAction(
      final UUID organizationId, final UUID workspaceId, final UUID teamId) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + WORKSPACES_PATH_SEGMENT
        + workspaceId
        + "/teams/"
        + teamId
        + "/assign-role";
  }

  private static String assignRoleToNoTeamAction(
      final UUID organizationId, final UUID workspaceId) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + WORKSPACES_PATH_SEGMENT
        + workspaceId
        + "/roles/assign-role";
  }

  // Live UX request, 2026-10-02 (supersedes the 2026-09-27 version of this same comment): one role
  // at a time, workspace-wide — eligibleAccounts is every account in this Organization
  // (OrganizationAccountDirectory) EXCLUDING one that already holds ANY role ANYWHERE in this
  // Workspace, not just within this specific group (groupRoles — one team's own roles, or the
  // ungrouped set for the "No team" group) as before. An account already holding role QA in Team A
  // can no longer be assigned role Tester in Team A itself or any other team/group in this
  // Workspace — "Remove" (this page's own per-member action) must leave them roleless first. Only
  // Team hierarchy's own "Assign role" works this way: PlatformAccountWorkspaceRoleController's own
  // identical-shaped Users-tab action deliberately keeps replacing a role in place (that
  // controller's own Javadoc) — this is a workflow restriction specific to this one dashboard
  // entry point, not a domain invariant, so it isn't enforced inside
  // AssignWorkspaceRoleToAccountService itself.
  private void populateAssignRoleModel(
      final Model model,
      final UUID organizationId,
      final Workspace workspace,
      final List<WorkspaceRole> groupRoles,
      final String assignRoleAction) {
    candidates.addEligibleAccounts(model, organizationId, workspace, null);
    model.addAttribute(
        "assignRoleSearchAction",
        "/platform/dashboard/organizations/"
            + organizationId
            + WORKSPACES_PATH_SEGMENT
            + workspace.id()
            + "/assign-role/accounts");
    model.addAttribute("groupRoles", groupRoles);
    model.addAttribute("assignRoleAction", assignRoleAction);
  }

  // The picker's search-as-you-type: the same eligibility rules, a term typed by the operator,
  // answered as just the <option> list that replaces the select's contents.
  @GetMapping("/{workspaceId}/assign-role/accounts")
  public String searchEligibleAccounts(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @RequestParam(name = "q", required = false) final String searchTerm,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    candidates.addEligibleAccounts(model, organizationId, workspace, searchTerm);
    return ACCOUNT_OPTIONS_FRAGMENT;
  }

  // SonarCloud finding, 2026-09-28: processAssignRole's own 7 context values (organizationId,
  // workspace, accountId, roleId, groupRoles, ownerPlatformAccountId, assignRoleAction) all
  // describe the SAME thing — one in-flight "assign a role" attempt, already resolved once by
  // whichever of the two call sites (per-team, "No team") built it — bundled here rather than
  // passed positionally, unlike a constructor's own disparate collaborating ports.
  private record AssignRoleAttempt(
      UUID organizationId,
      Workspace workspace,
      UUID accountId,
      UUID roleId,
      List<WorkspaceRole> groupRoles,
      UUID ownerPlatformAccountId,
      String assignRoleAction) {}

  // Shared POST tail for both showAssignRoleFormForTeam/-ForNoTeam's own mutating counterparts.
  // Fires ROLE_ASSIGNED_EVENT on success — same HX-Trigger convention
  // PlatformAccountWorkspaceRoleController's own save() already establishes — so
  // workspace-teams-hierarchy.html's own hierarchy fragment (listening on document.body)
  // self-refreshes with the newly assigned member, no full page reload.
  private String processAssignRole(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AssignRoleAttempt attempt,
      final Model model) {
    final boolean roleBelongsToThisGroup =
        attempt.groupRoles().stream().anyMatch(role -> role.id().equals(attempt.roleId()));
    if (!roleBelongsToThisGroup) {
      // The popup's own Role <select> only ever offers this group's own roles — reaching this
      // means the submitted roleId was tampered with, not a real user mistake.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    // Live UX request, 2026-10-02: Team hierarchy's own "one role at a time, workspace-wide" rule
    // — see populateAssignRoleModel's own Javadoc for the full reasoning. The popup's own User
    // <select> already excludes every account this would reject, so reaching this normally means
    // a stale form (someone else assigned this account a role elsewhere while this popup was
    // open), not a real user mistake — re-rendered as a form error, not a raw 400, since it's a
    // real, reachable outcome rather than tampering.
    if (candidates.accountsWithAnyRole(attempt.workspace()).contains(attempt.accountId())) {
      model.addAttribute(ACCOUNT_ALREADY_HAS_A_ROLE_ERROR_ATTRIBUTE, true);
      populateAssignRoleModel(
          model,
          attempt.organizationId(),
          attempt.workspace(),
          attempt.groupRoles(),
          attempt.assignRoleAction());
      return ASSIGN_ROLE_FORM_FRAGMENT;
    }

    try {
      assignRoleToAccountUseCase.handle(
          new AssignWorkspaceRoleToAccountCommand(
              attempt.workspace().id(),
              attempt.accountId(),
              attempt.roleId(),
              AuditActor.platformAccount(attempt.ownerPlatformAccountId())));
    } catch (final AccountNotInOrganizationException | WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    // No CannotDemoteLastAdminException catch here (confirmed with the user, 2026-10-01):
    // AssignWorkspaceRoleToAccountService deliberately no longer enforces that guard for this
    // operator-driven dashboard action — see that service's own Javadoc. The other
    // CannotDemoteLastAdminException catch blocks in this class (delete role / delete team /
    // remove member) are untouched and keep enforcing it.

    // Real bug found live, 2026-09-27: this used to always return the bare HTML fragment,
    // breaking "every action here works identically with JavaScript disabled" (this class's own
    // Javadoc) for a plain form submit — a JS-disabled browser would render a chrome-less "Role
    // assigned." snippet instead of navigating anywhere. A plain submit now redirects back to the
    // Teams tab, same as every other mutation in this controller.
    if (isHtmxRequest(request)) {
      response.setHeader("HX-Trigger", ROLE_ASSIGNED_EVENT);
      return ASSIGN_ROLE_SAVED_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX
        + attempt.organizationId()
        + WORKSPACES_PATH_SEGMENT
        + attempt.workspace().id()
        + "/teams";
  }
}
