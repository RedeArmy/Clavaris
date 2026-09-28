package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.WorkspaceRoleAlreadyInAnotherTeamException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountCommand;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceCommand;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationQuery;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersQuery;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025/ADR-0028: the dashboard's own Workspace management — creating a Workspace from the
 * Organization-detail page, and (as of this Teams &amp; Roles redesign) managing this Workspace's
 * own Teams and the Organization's WorkspaceRoles grouped into them, from the Workspace-detail page
 * it links to. Every write here goes through the exact same use cases {@code /api/v1/admin/**}
 * already exposes ({@link CreateWorkspaceUseCase}, {@link CreateWorkspaceTeamUseCase}, {@link
 * CreateWorkspaceRoleUseCase}, {@link DeleteWorkspaceRoleUseCase}, ...) — this controller adds a
 * second, session-authenticated caller, not a second implementation. See {@code
 * CreateWorkspaceCommand}'s own Javadoc for why an {@link AuditActor#platformAccount} actor on this
 * path is a deliberate widening of an established precedent ({@link
 * PlatformOrganizationDashboardController}'s own {@code create}), not an oversight.
 *
 * <p>SDE-III redesign, 2026-09-27 (live UX request): the old "Members" section (add/remove a
 * member, change their role) has been removed from this page entirely — {@code
 * AddWorkspaceMemberUseCase}/ {@code ChangeWorkspaceMemberRoleUseCase}/{@code
 * RemoveWorkspaceMemberUseCase} remain fully intact and still reachable via the REST admin API
 * ({@code AddWorkspaceMemberController} et al.), only this controller's own dashboard wiring to
 * them is gone — a deliberate, confirmed choice, not an oversight. {@code
 * RemoveRoleFromWorkspaceTeamUseCase} similarly loses its only caller here (the old "Remove from
 * team" button is replaced by "Delete" on the role itself, per the same redesign) but its own
 * application-layer implementation and tests are untouched — dashboard wiring removed, not the
 * underlying capability.
 *
 * <p>The Teams &amp; Roles section now lets an owner create a role and assign it to an existing
 * team in one popup (composes {@link CreateWorkspaceRoleUseCase} with {@link
 * AddRoleToWorkspaceTeamUseCase}), and modify/delete a role directly from its team's own dropdown —
 * "Modify" links to the existing full Configure &gt; Workspace Roles edit page (same {@code
 * UpdateWorkspaceRoleUseCase}-backed form, not duplicated here); "Delete" calls {@link
 * DeleteWorkspaceRoleUseCase} directly, same {@code role.reserved()} guard {@code
 * workspace-role-detail.html} already applies (a reserved role never renders a delete control).
 *
 * <p>Every method resolves {@code organizationId} through {@link
 * GetOrganizationForPlatformAccountUseCase} and, where a {@code workspaceId} is also in the URL,
 * {@link GetWorkspaceForOrganizationUseCase} — never a bare repository call — so a workspaceId
 * belonging to an Organization this {@code PlatformAccount} doesn't own resolves identically to
 * "doesn't exist" (a 404), same anti-enumeration posture as {@link
 * PlatformOrganizationDetailController}. {@code roleId} is always resolved from this Organization's
 * own {@link ListWorkspaceRolesForOrganizationUseCase} result first (see {@link #requireOwnedRole})
 * — same reasoning {@link PlatformWorkspaceRoleController}'s own Javadoc documents for {@code
 * DeleteWorkspaceRoleCommand} carrying no {@code organizationId} of its own.
 *
 * <p>Same HTMX-fragment-vs-redirect branching as {@link PlatformOrganizationDashboardController}:
 * an {@code HX-Request} gets back just the relevant fragment (200, in place); a plain form submit
 * keeps a full {@code redirect:} on success and a full re-render on a validation/business error —
 * HTMX is progressive enhancement, every action here works identically with JavaScript disabled.
 */
// PMD.ExcessiveImports: every import backs a real, distinct collaborator or exception this
// controller genuinely needs across Workspace/Team/Role mutation — same "wiring, not sprawl"
// reasoning OrganizationUseCaseConfig's own class-level Javadoc documents for an identical
// situation.
// PMD.AvoidDuplicateLiterals: "PMD.OnlyOneReturn", repeated once per handler method that
// legitimately needs it, same false-positive class ContentSecurityPolicyHeaderWriter's own
// identical class-level suppression already documents.
// PMD.TooManyMethods/PMD.CouplingBetweenObjects (TD-PERF-020's own pagination wiring, ADR-0028's
// Teams section, and this Teams & Roles redesign, combined): this controller already owns
// Workspace/Team/Role-mutating endpoints plus their shared read-model helpers; splitting it along
// resource lines (Workspaces vs. Teams vs. Roles) is a real, larger refactor for a future pass, not
// a fix this redesign should bundle in.
@SuppressWarnings({
  "PMD.LongVariable",
  "PMD.ExcessiveImports",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects"
})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspaces")
public class PlatformWorkspaceController {

  private static final String ORGANIZATION_DETAIL_VIEW =
      "organization/platform/organization-detail";
  private static final String WORKSPACES_FRAGMENT = ORGANIZATION_DETAIL_VIEW + " :: workspaces";
  private static final String WORKSPACE_DETAIL_VIEW = "organization/platform/workspace-detail";
  private static final String TEAMS_FRAGMENT = WORKSPACE_DETAIL_VIEW + " :: teams";
  private static final String TEAMS_HIERARCHY_VIEW =
      "organization/platform/workspace-teams-hierarchy";
  private static final String TEAMS_HIERARCHY_FRAGMENT = TEAMS_HIERARCHY_VIEW + " :: hierarchy";
  private static final String ASSIGN_ROLE_FORM_FRAGMENT =
      "organization/platform/fragments/team-assign-role-form :: assignRoleForm";
  private static final String ASSIGN_ROLE_SAVED_FRAGMENT =
      "organization/platform/fragments/team-assign-role-form :: assignRoleSaved";
  // htmx's own response-header convention — same ADR-0029 event name/purpose
  // PlatformAccountWorkspaceRoleController's own identical constant documents: workspace-
  // teams-hierarchy.html's own hierarchy fragment listens for this on document.body to
  // self-refresh after a successful assignment.
  private static final String ROLE_ASSIGNED_EVENT = "workspace-role-assigned";
  private static final String CREATE_TEAM_FORM_ATTRIBUTE = "createTeamForm";
  private static final String CREATE_ROLE_FORM_ATTRIBUTE = "createRoleForm";
  private static final String ORGANIZATION_ATTRIBUTE = "organization";
  private static final String ORGANIZATIONS_REDIRECT_PREFIX =
      "redirect:/platform/dashboard/organizations/";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // PlatformOrganizationDashboardController's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetWorkspaceForOrganizationUseCase getWorkspace;
  private final ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CreateWorkspaceUseCase createWorkspace;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private final ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds;
  private final CreateWorkspaceTeamUseCase createTeamUseCase;
  private final RenameWorkspaceTeamUseCase renameTeamUseCase;
  private final DeleteWorkspaceTeamUseCase deleteTeamUseCase;
  private final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase;
  private final CreateWorkspaceRoleUseCase createRoleUseCase;
  private final DeleteWorkspaceRoleUseCase deleteRoleUseCase;
  private final ListWorkspaceMembersUseCase listMembersUseCase;
  private final OrganizationAccountDirectory accountDirectory;
  private final AssignWorkspaceRoleToAccountUseCase assignRoleToAccountUseCase;

  // java:S107/PMD.ExcessiveParameterList: one parameter per collaborating port — same rationale
  // as every other multi-collaborator constructor in this codebase; this controller's own Teams &
  // Roles surface pushed this well past PMD's default threshold (10) — wiring, not sprawl, same
  // reasoning OrganizationUseCaseConfig's own class-level Javadoc documents for an identical
  // situation.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public PlatformWorkspaceController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetWorkspaceForOrganizationUseCase getWorkspace,
      final ListWorkspacesForOrganizationPagedUseCase listWorkspaces,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CreateWorkspaceUseCase createWorkspace,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final ListWorkspaceTeamsForWorkspaceUseCase listTeams,
      final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds,
      final ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds,
      final CreateWorkspaceTeamUseCase createTeamUseCase,
      final RenameWorkspaceTeamUseCase renameTeamUseCase,
      final DeleteWorkspaceTeamUseCase deleteTeamUseCase,
      final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase,
      final CreateWorkspaceRoleUseCase createRoleUseCase,
      final DeleteWorkspaceRoleUseCase deleteRoleUseCase,
      final ListWorkspaceMembersUseCase listMembersUseCase,
      final OrganizationAccountDirectory accountDirectory,
      final AssignWorkspaceRoleToAccountUseCase assignRoleToAccountUseCase) {
    this.getOrganization = getOrganization;
    this.getWorkspace = getWorkspace;
    this.listWorkspaces = listWorkspaces;
    this.listRoles = listRoles;
    this.createWorkspace = createWorkspace;
    this.currentPlatformAccount = currentPlatformAccount;
    this.listTeams = listTeams;
    this.listTeamRoleIds = listTeamRoleIds;
    this.listGroupedRoleIds = listGroupedRoleIds;
    this.createTeamUseCase = createTeamUseCase;
    this.renameTeamUseCase = renameTeamUseCase;
    this.deleteTeamUseCase = deleteTeamUseCase;
    this.addRoleToTeamUseCase = addRoleToTeamUseCase;
    this.createRoleUseCase = createRoleUseCase;
    this.deleteRoleUseCase = deleteRoleUseCase;
    this.listMembersUseCase = listMembersUseCase;
    this.accountDirectory = accountDirectory;
    this.assignRoleToAccountUseCase = assignRoleToAccountUseCase;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("workspaceForm") final CreateWorkspaceForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    if (bindingResult.hasErrors()) {
      // A plain (non-HTMX) validation error re-renders the WHOLE organization-detail page — same
      // reason "organization"/"workspaces" are populated here too, not just on
      // PlatformOrganizationDetailController's own GET.
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      addWorkspacesToModel(model, organizationId, KeysetPageRequest.first());
      return isHtmxRequest(request) ? WORKSPACES_FRAGMENT : ORGANIZATION_DETAIL_VIEW;
    }

    // TD-SEC-007: same self-service posture as CreateOrganizationCommand's own dashboard caller —
    // organizationId was just resolved through requireOwnedOrganization above, so this
    // PlatformAccount genuinely owns the Organization it's adding a Workspace to.
    createWorkspace.handle(
        new CreateWorkspaceCommand(
            organizationId, form.getName(), AuditActor.platformAccount(ownerPlatformAccountId)));

    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      // The first page (no cursor) — a newly-created Workspace sorts first (newest-first
      // ordering), same reasoning PlatformOrganizationDashboardController's own identical
      // create() already documents.
      addWorkspacesToModel(model, organizationId, KeysetPageRequest.first());
      model.addAttribute("workspaceForm", new CreateWorkspaceForm());
      return WORKSPACES_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX + organizationId;
  }

  private void addWorkspacesToModel(
      final Model model, final UUID organizationId, final KeysetPageRequest pageRequest) {
    final KeysetPage<Workspace> workspacesPage =
        listWorkspaces.handle(
            new ListWorkspacesForOrganizationPagedQuery(organizationId, pageRequest));
    model.addAttribute("workspaces", workspacesPage.content());
    model.addAttribute("workspacesPage", workspacesPage);
  }

  @GetMapping("/{workspaceId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateTeamsModel(model, workspace);
    return WORKSPACE_DETAIL_VIEW;
  }

  // SDE-III addition, 2026-09-27 (live UX request): a second, read-only tab on this same page —
  // the Team -> Role -> Members hierarchy the Manage tab's own CRUD builds. A real GET route (not
  // an HTMX-only fragment), same "each tab is its own bookmarkable, no-JS-required page" convention
  // org-tabs.html's own Organization-level tabs already establish; workspace-tabs.html is this
  // module's own two-tab equivalent. Also doubles as the self-refresh target the "Assign role"
  // popups below fire on success (workspace-teams-hierarchy.html's own hx-trigger), hence the HTMX
  // fragment branch — same shape every other mutating section on this dashboard already has, just
  // triggered by a custom event instead of a click.
  @GetMapping("/{workspaceId}/teams")
  public String showTeamsHierarchy(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateTeamsHierarchyModel(model, workspace);
    return isHtmxRequest(request) ? TEAMS_HIERARCHY_FRAGMENT : TEAMS_HIERARCHY_VIEW;
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
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> teamRoles = requireOwnedTeamRoles(workspace, teamId);
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
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> teamRoles = requireOwnedTeamRoles(workspace, teamId);
    return processAssignRole(
        request,
        response,
        organizationId,
        workspace,
        accountId,
        roleId,
        teamRoles,
        ownerPlatformAccountId,
        assignRoleToTeamAction(organizationId, workspaceId, teamId),
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
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> ungroupedRoles = loadTeamsAndRoles(workspace).ungroupedRoles();
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
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    final List<WorkspaceRole> ungroupedRoles = loadTeamsAndRoles(workspace).ungroupedRoles();
    return processAssignRole(
        request,
        response,
        organizationId,
        workspace,
        accountId,
        roleId,
        ungroupedRoles,
        ownerPlatformAccountId,
        assignRoleToNoTeamAction(organizationId, workspaceId),
        model);
  }

  // ADR-0028: the Workspace-detail page's own Teams & Roles section — create/rename/delete a team,
  // create a WorkspaceRole and assign it to a team in one popup, add one of this Organization's
  // already-existing ungrouped roles to a team, and modify/delete a role directly. Every write here
  // goes through the exact same use cases a future REST admin API would (none exists yet for Teams
  // — dashboard-only in this increment, same "no permission semantics of its own" posture the ADR's
  // own §4/open-question-3 documents).
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/teams")
  public String createTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @Valid @ModelAttribute(CREATE_TEAM_FORM_ATTRIBUTE) final CreateWorkspaceTeamForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    if (bindingResult.hasErrors()) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    try {
      createTeamUseCase.handle(
          new CreateWorkspaceTeamCommand(
              workspaceId, form.getName(), AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final DuplicateWorkspaceTeamNameException _) {
      model.addAttribute("duplicateTeamNameError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/teams/{teamId}/rename")
  public String renameTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      @RequestParam final String name,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);

    try {
      renameTeamUseCase.handle(
          new RenameWorkspaceTeamCommand(
              workspaceId, teamId, name, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final DuplicateWorkspaceTeamNameException _) {
      model.addAttribute("duplicateTeamNameError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  @PostMapping("/{workspaceId}/teams/{teamId}/delete")
  public String deleteTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);

    try {
      deleteTeamUseCase.handle(
          new DeleteWorkspaceTeamCommand(
              workspaceId, teamId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  // Attaches one of this Organization's already-existing, currently-ungrouped roles to a team —
  // kept alongside the "create a new role" popup below for the case where a role was created (or
  // left ungrouped after its own team was deleted) via Configure > Workspace Roles without one.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/teams/{teamId}/roles")
  public String addRoleToTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      @RequestParam final UUID roleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);

    try {
      addRoleToTeamUseCase.handle(
          new AddRoleToWorkspaceTeamCommand(
              workspaceId, teamId, roleId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException | WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final WorkspaceRoleAlreadyInAnotherTeamException _) {
      model.addAttribute("roleAlreadyInAnotherTeamError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  // SDE-III redesign, 2026-09-27: creates a brand-new WorkspaceRole and assigns it to an existing
  // team in one popup submit — composes CreateWorkspaceRoleUseCase with
  // AddRoleToWorkspaceTeamUseCase, same "two use cases, one form" shape addMember's own removed
  // Account+WorkspaceMembership composition used. parentRoleId/permissions are deliberately absent
  // from this popup's form (Set.of()/null) — those stay editable only from the full Configure >
  // Workspace Roles page ("Modify" links there, never duplicated into a second form here).
  // WorkspaceRoleAlreadyInAnotherTeamException isn't caught here: the role this popup just created
  // has a freshly-minted, never-before-seen id, so it cannot already belong to another team — not a
  // race this path can hit, unlike addRoleToTeam's own identical catch for a pre-existing role id.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/roles")
  public String createRole(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @Valid @ModelAttribute(CREATE_ROLE_FORM_ATTRIBUTE) final CreateWorkspaceTeamRoleForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    if (bindingResult.hasErrors()) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    final WorkspaceRole role;
    try {
      role =
          createRoleUseCase.handle(
              new CreateWorkspaceRoleCommand(
                  organizationId,
                  form.getName(),
                  null,
                  Set.of(),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final DuplicateWorkspaceRoleNameException _) {
      model.addAttribute("duplicateRoleNameError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    try {
      addRoleToTeamUseCase.handle(
          new AddRoleToWorkspaceTeamCommand(
              workspaceId,
              form.getTeamId(),
              role.id(),
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException _) {
      // The popup's own team <select> only ever offers this Workspace's own real teams — reaching
      // this means the submitted teamId was tampered with, not a real user mistake.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  // SDE-III redesign, 2026-09-27: deletes a WorkspaceRole entirely (not just its team grouping) —
  // roleId is resolved through requireOwnedRole first, same anti-enumeration posture
  // PlatformWorkspaceRoleController's own delete() already establishes for this exact command
  // (DeleteWorkspaceRoleCommand carries no organizationId of its own). CannotDeleteReservedWork
  // spaceRoleException isn't reachable through this UI — the reserved role never renders a delete
  // control here either, same th:unless="${role.reserved()}" guard workspace-role-detail.html's own
  // delete card already uses.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/roles/{roleId}/delete")
  public String deleteRole(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID roleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    requireOwnedRole(organizationId, roleId);

    try {
      deleteRoleUseCase.handle(
          new DeleteWorkspaceRoleCommand(
              roleId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDeleteReservedWorkspaceRoleException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } catch (final WorkspaceRoleStillAssignedException _) {
      model.addAttribute("roleStillAssignedError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    } catch (final WorkspaceRoleHasChildRolesException _) {
      model.addAttribute("roleHasChildRolesError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  // Shared "a team/role mutation just succeeded" tail — same rationale as
  // PlatformOrganizationDashboardController's own identical shape for its own resource.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String afterTeamMutation(
      final HttpServletRequest request,
      final Organization organization,
      final Workspace workspace,
      final Model model) {
    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return TEAMS_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX + organization.id() + "/workspaces/" + workspace.id();
  }

  // ADR-0028: teams (List<WorkspaceTeam>), the roles grouped into each one (Map<UUID, List
  // <WorkspaceRole>>, keyed by teamId), and every Organization role with no team association in
  // this Workspace ("ungrouped"). Reloads this Organization's own role catalog on every call — a
  // small, deliberate redundant read (this is a low-traffic admin dashboard).
  private void populateTeamsModel(final Model model, final Workspace workspace) {
    // Self-sufficient on "workspace" — several call sites are error/success paths that never
    // otherwise set it; a missing "workspace" here is a null workspace.name()
    // SpelEvaluationException on the very next render, not a graceful no-op.
    model.addAttribute("workspace", workspace);
    final TeamsAndRoles teamsAndRoles = loadTeamsAndRoles(workspace);

    model.addAttribute("teams", teamsAndRoles.teams());
    model.addAttribute("teamRoles", teamsAndRoles.rolesByTeamId());
    model.addAttribute("ungroupedRoles", teamsAndRoles.ungroupedRoles());
    model.addAttribute(CREATE_TEAM_FORM_ATTRIBUTE, new CreateWorkspaceTeamForm());
    model.addAttribute(CREATE_ROLE_FORM_ATTRIBUTE, new CreateWorkspaceTeamRoleForm());
  }

  // SDE-III addition, 2026-09-27: the Teams tab's own read-only hierarchy — same
  // teams/rolesByTeamId
  // /ungroupedRoles as the Manage tab (loadTeamsAndRoles is shared, not duplicated), plus every
  // member currently holding each role, keyed by roleId. Every roleId this Organization has gets a
  // (possibly empty) entry — never a missing map key — so the template never has to null-check
  // accountIdsByRoleId.get(...) the way it would if Collectors.groupingBy's own "only present keys
  // that had at least one match" behavior were used directly.
  private void populateTeamsHierarchyModel(final Model model, final Workspace workspace) {
    model.addAttribute("workspace", workspace);
    final TeamsAndRoles teamsAndRoles = loadTeamsAndRoles(workspace);

    final Map<UUID, List<UUID>> accountIdsByRoleId = new LinkedHashMap<>();
    teamsAndRoles.rolesByTeamId().values().stream()
        .flatMap(List::stream)
        .forEach(role -> accountIdsByRoleId.put(role.id(), new ArrayList<>()));
    teamsAndRoles
        .ungroupedRoles()
        .forEach(role -> accountIdsByRoleId.put(role.id(), new ArrayList<>()));
    listMembersUseCase.handle(new ListWorkspaceMembersQuery(workspace.id())).stream()
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
  }

  private record TeamsAndRoles(
      List<WorkspaceTeam> teams,
      Map<UUID, List<WorkspaceRole>> rolesByTeamId,
      List<WorkspaceRole> ungroupedRoles) {}

  private TeamsAndRoles loadTeamsAndRoles(final Workspace workspace) {
    final Map<UUID, WorkspaceRole> rolesById =
        listRoles
            .handle(new ListWorkspaceRolesForOrganizationQuery(workspace.organizationId()))
            .stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    final List<WorkspaceTeam> teams =
        listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspace.id()));
    final Map<UUID, List<WorkspaceRole>> rolesByTeamId = new LinkedHashMap<>();
    for (final WorkspaceTeam team : teams) {
      final List<WorkspaceRole> teamRoles =
          listTeamRoleIds.handle(new ListWorkspaceTeamRoleIdsQuery(team.id())).stream()
              .map(rolesById::get)
              .filter(Objects::nonNull)
              .sorted(Comparator.comparing(WorkspaceRole::name))
              .toList();
      rolesByTeamId.put(team.id(), teamRoles);
    }

    final Set<UUID> groupedRoleIds =
        listGroupedRoleIds.handle(new ListGroupedWorkspaceRoleIdsQuery(workspace.id()));
    final List<WorkspaceRole> ungroupedRoles =
        rolesById.values().stream()
            .filter(role -> !groupedRoleIds.contains(role.id()))
            .sorted(Comparator.comparing(WorkspaceRole::name))
            .toList();

    return new TeamsAndRoles(teams, rolesByTeamId, ungroupedRoles);
  }

  // Resolves teamId's own roles from THIS workspace's real teams (loadTeamsAndRoles's own
  // rolesByTeamId map is keyed only by teamIds that genuinely belong to this workspace) — a teamId
  // that isn't one of them (wrong workspace, wrong organization, or just made up) 404s here before
  // either "Assign role" handler below does anything else with it.
  private List<WorkspaceRole> requireOwnedTeamRoles(final Workspace workspace, final UUID teamId) {
    final List<WorkspaceRole> teamRoles = loadTeamsAndRoles(workspace).rolesByTeamId().get(teamId);
    if (teamRoles == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return teamRoles;
  }

  private static String assignRoleToTeamAction(
      final UUID organizationId, final UUID workspaceId, final UUID teamId) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/workspaces/"
        + workspaceId
        + "/teams/"
        + teamId
        + "/assign-role";
  }

  private static String assignRoleToNoTeamAction(
      final UUID organizationId, final UUID workspaceId) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/workspaces/"
        + workspaceId
        + "/roles/assign-role";
  }

  // Live UX request, 2026-09-27: eligibleAccounts is every account in this Organization
  // (OrganizationAccountDirectory) EXCLUDING one that already holds a role within this specific
  // group (groupRoles — one team's own roles, or the ungrouped set for the "No team" group) — an
  // account with a role in a DIFFERENT group, or no role at all, is still eligible here.
  private void populateAssignRoleModel(
      final Model model,
      final UUID organizationId,
      final Workspace workspace,
      final List<WorkspaceRole> groupRoles,
      final String assignRoleAction) {
    final Set<UUID> groupRoleIds =
        groupRoles.stream().map(WorkspaceRole::id).collect(Collectors.toSet());
    final Set<UUID> accountsAlreadyInGroup =
        listMembersUseCase.handle(new ListWorkspaceMembersQuery(workspace.id())).stream()
            .filter(
                membership ->
                    membership.roleId() != null && groupRoleIds.contains(membership.roleId()))
            .map(WorkspaceMembership::accountId)
            .collect(Collectors.toSet());
    final List<OrganizationAccountSummary> eligibleAccounts =
        accountDirectory.listAccountsForOrganization(organizationId).stream()
            .filter(account -> !accountsAlreadyInGroup.contains(account.accountId()))
            .sorted(Comparator.comparing(OrganizationAccountSummary::label))
            .toList();

    model.addAttribute("eligibleAccounts", eligibleAccounts);
    model.addAttribute("groupRoles", groupRoles);
    model.addAttribute("assignRoleAction", assignRoleAction);
  }

  // Shared POST tail for both showAssignRoleFormForTeam/-ForNoTeam's own mutating counterparts.
  // Fires ROLE_ASSIGNED_EVENT on success — same HX-Trigger convention
  // PlatformAccountWorkspaceRoleController's own save() already establishes — so
  // workspace-teams-hierarchy.html's own hierarchy fragment (listening on document.body)
  // self-refreshes with the newly assigned member, no full page reload.
  // PMD.ExcessiveParameterList: one parameter per genuinely distinct piece of context its two call
  // sites (per-team, "No team") each already resolved — wiring shared between them, not sprawl,
  // same reasoning this controller's own constructor documents for an identical threshold trip.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.ExcessiveParameterList"})
  private String processAssignRole(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final Workspace workspace,
      final UUID accountId,
      final UUID roleId,
      final List<WorkspaceRole> groupRoles,
      final UUID ownerPlatformAccountId,
      final String assignRoleAction,
      final Model model) {
    final boolean roleBelongsToThisGroup =
        groupRoles.stream().anyMatch(role -> role.id().equals(roleId));
    if (!roleBelongsToThisGroup) {
      // The popup's own Role <select> only ever offers this group's own roles — reaching this
      // means the submitted roleId was tampered with, not a real user mistake.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    try {
      assignRoleToAccountUseCase.handle(
          new AssignWorkspaceRoleToAccountCommand(
              workspace.id(),
              accountId,
              roleId,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final AccountNotInOrganizationException | WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDemoteLastAdminException _) {
      model.addAttribute("cannotDemoteLastAdminError", true);
      populateAssignRoleModel(model, organizationId, workspace, groupRoles, assignRoleAction);
      return ASSIGN_ROLE_FORM_FRAGMENT;
    }

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
        + organizationId
        + "/workspaces/"
        + workspace.id()
        + "/teams";
  }

  private Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Workspace requireOwnedWorkspace(final UUID organizationId, final UUID workspaceId) {
    return getWorkspace
        .handle(new GetWorkspaceForOrganizationQuery(organizationId, workspaceId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Same anti-enumeration posture PlatformWorkspaceRoleController's own requireOwnedRole
  // establishes for the identical DeleteWorkspaceRoleCommand/UpdateWorkspaceRoleCommand shape
  // (neither carries its own organizationId) — a roleId belonging to a different Organization 404s
  // here before DeleteWorkspaceRoleUseCase is ever called.
  private void requireOwnedRole(final UUID organizationId, final UUID roleId) {
    final boolean owned =
        listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId)).stream()
            .anyMatch(role -> role.id().equals(roleId));
    if (!owned) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  // Same rationale as PlatformOrganizationDashboardController's own identical method.
  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
