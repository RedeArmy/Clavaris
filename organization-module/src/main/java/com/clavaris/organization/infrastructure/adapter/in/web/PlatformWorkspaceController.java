package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.WorkspaceRoleAlreadyInAnotherTeamException;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberCommand;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceCommand;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationQuery;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedUseCase;
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
import com.clavaris.organization.application.usecase.removerolefromworkspaceteam.RemoveRoleFromWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.removerolefromworkspaceteam.RemoveRoleFromWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.CannotRemoveLastAdminException;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberCommand;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
 * ADR-0025: the dashboard's own Workspace management — creating a Workspace from the
 * Organization-detail page, and adding/removing members and changing their role from the
 * Workspace-detail page it links to. Every write here goes through the exact same use cases {@code
 * /api/v1/admin/**} already exposes ({@link CreateWorkspaceUseCase}, {@link
 * AddWorkspaceMemberUseCase}, {@link ChangeWorkspaceMemberRoleUseCase}, {@link
 * RemoveWorkspaceMemberUseCase}) — this controller adds a second, session-authenticated caller, not
 * a second implementation. See {@code CreateWorkspaceCommand}'s own Javadoc for why an {@link
 * AuditActor#platformAccount} actor on this path is a deliberate widening of an established
 * precedent ({@link PlatformOrganizationDashboardController}'s own {@code create}), not an
 * oversight.
 *
 * <p>Every method resolves {@code organizationId} through {@link
 * GetOrganizationForPlatformAccountUseCase} and, where a {@code workspaceId} is also in the URL,
 * {@link GetWorkspaceForOrganizationUseCase} — never a bare repository call — so a workspaceId
 * belonging to an Organization this {@code PlatformAccount} doesn't own resolves identically to
 * "doesn't exist" (a 404), same anti-enumeration posture as {@link
 * PlatformOrganizationDetailController}.
 *
 * <p>Same HTMX-fragment-vs-redirect branching as {@link PlatformOrganizationDashboardController}:
 * an {@code HX-Request} gets back just the relevant fragment (200, in place); a plain form submit
 * keeps a full {@code redirect:} on success and a full re-render on a validation/business error —
 * HTMX is progressive enhancement, every action here works identically with JavaScript disabled.
 */
// PMD.ExcessiveImports: eight collaborators (four use cases plus their commands/exceptions) is what
// wiring one controller to four distinct Workspace-mutating use cases actually costs — same
// "wiring, not sprawl" reasoning OrganizationUseCaseConfig's own class-level Javadoc documents for
// an identical situation.
// PMD.AvoidDuplicateLiterals: two real repeats, neither worth restructuring around — "memberForm"
// (extracted to MEMBER_FORM_ATTRIBUTE below anyway, but @ModelAttribute's own annotation argument
// must still be the literal, not the constant, so one duplicate remains) and "PMD.OnlyOneReturn",
// repeated once per handler method that legitimately needs it, same false-positive class
// ContentSecurityPolicyHeaderWriter's own identical class-level suppression already documents.
// PMD.TooManyMethods/PMD.CouplingBetweenObjects (TD-PERF-020's own pagination wiring —
// addWorkspacesToModel, isHtmxRequest, the two new Paged use-case fields — pushed both past their
// default thresholds): this controller already owns five distinct Workspace/Member-mutating
// endpoints plus their shared read-model helpers; splitting it along resource lines (Workspaces vs.
// Members) is a real, larger refactor for a future pass, not a fix this pagination increment should
// bundle in.
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
  private static final String MEMBERS_FRAGMENT = WORKSPACE_DETAIL_VIEW + " :: members";
  private static final String TEAMS_FRAGMENT = WORKSPACE_DETAIL_VIEW + " :: teams";
  private static final String MEMBER_FORM_ATTRIBUTE = "memberForm";
  private static final String CREATE_TEAM_FORM_ATTRIBUTE = "createTeamForm";
  private static final String ORGANIZATION_ATTRIBUTE = "organization";
  private static final String ORGANIZATIONS_REDIRECT_PREFIX =
      "redirect:/platform/dashboard/organizations/";

  // HTMX's own request header (https://htmx.org/reference/#request_headers) — same convention as
  // PlatformOrganizationDashboardController's own identical constant.
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetWorkspaceForOrganizationUseCase getWorkspace;
  private final ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private final ListWorkspaceMembersPagedUseCase listMembers;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CreateWorkspaceUseCase createWorkspace;
  private final AddWorkspaceMemberUseCase addMemberUseCase;
  private final ChangeWorkspaceMemberRoleUseCase changeMemberRole;
  private final RemoveWorkspaceMemberUseCase removeMemberUseCase;
  private final CurrentPlatformAccountResolver currentPlatformAccount;
  private final ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private final ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds;
  private final CreateWorkspaceTeamUseCase createTeamUseCase;
  private final RenameWorkspaceTeamUseCase renameTeamUseCase;
  private final DeleteWorkspaceTeamUseCase deleteTeamUseCase;
  private final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase;
  private final RemoveRoleFromWorkspaceTeamUseCase removeRoleFromTeamUseCase;

  // java:S107/PMD.ExcessiveParameterList: one parameter per collaborating port — same rationale
  // as every other multi-collaborator constructor in this codebase; ADR-0028's own Teams section
  // (8 further collaborators: 3 read use cases, 5 mutating ones) pushed this well past PMD's
  // default threshold (10) — wiring, not sprawl, same reasoning OrganizationUseCaseConfig's own
  // class-level Javadoc documents for an identical situation.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public PlatformWorkspaceController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetWorkspaceForOrganizationUseCase getWorkspace,
      final ListWorkspacesForOrganizationPagedUseCase listWorkspaces,
      final ListWorkspaceMembersPagedUseCase listMembers,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CreateWorkspaceUseCase createWorkspace,
      final AddWorkspaceMemberUseCase addMemberUseCase,
      final ChangeWorkspaceMemberRoleUseCase changeMemberRole,
      final RemoveWorkspaceMemberUseCase removeMemberUseCase,
      final CurrentPlatformAccountResolver currentPlatformAccount,
      final ListWorkspaceTeamsForWorkspaceUseCase listTeams,
      final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds,
      final ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds,
      final CreateWorkspaceTeamUseCase createTeamUseCase,
      final RenameWorkspaceTeamUseCase renameTeamUseCase,
      final DeleteWorkspaceTeamUseCase deleteTeamUseCase,
      final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase,
      final RemoveRoleFromWorkspaceTeamUseCase removeRoleFromTeamUseCase) {
    this.getOrganization = getOrganization;
    this.getWorkspace = getWorkspace;
    this.listWorkspaces = listWorkspaces;
    this.listMembers = listMembers;
    this.listRoles = listRoles;
    this.createWorkspace = createWorkspace;
    this.addMemberUseCase = addMemberUseCase;
    this.changeMemberRole = changeMemberRole;
    this.removeMemberUseCase = removeMemberUseCase;
    this.currentPlatformAccount = currentPlatformAccount;
    this.listTeams = listTeams;
    this.listTeamRoleIds = listTeamRoleIds;
    this.listGroupedRoleIds = listGroupedRoleIds;
    this.createTeamUseCase = createTeamUseCase;
    this.renameTeamUseCase = renameTeamUseCase;
    this.deleteTeamUseCase = deleteTeamUseCase;
    this.addRoleToTeamUseCase = addRoleToTeamUseCase;
    this.removeRoleFromTeamUseCase = removeRoleFromTeamUseCase;
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
      // PlatformOrganizationDetailController's own GET. SDE-III review, 2026-09-19: this page no
      // longer has a Rate Limit section of its own (moved to its own Configure sub-page), so
      // rateLimitPolicy/rateLimitForm no longer need populating here either.
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

  // TD-PERF-020 (keyset revision, 2026-09-14): ?after=/?before= carry an opaque KeysetCursor
  // token — see PlatformOrganizationDashboardController's own identical parameter for the full
  // reasoning.
  @GetMapping("/{workspaceId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
    populateMembersModel(
        model, organization, workspace, KeysetPageRequest.fromCursors(after, before));
    // ADR-0028: populated unconditionally, even on the HTMX members-pagination branch below —
    // harmless extra model data the members fragment's own Thymeleaf selector simply never
    // renders, and it means a plain (non-HTMX) full-page GET always has the Teams section's own
    // data ready without a second branch to remember.
    populateTeamsModel(model, workspace);
    // TD-PERF-020: same "a pagination link is itself an hx-get, and its hx-target can't safely
    // receive a full HTML document" reasoning PlatformOrganizationDetailController's own identical
    // branching documents.
    return isHtmxRequest(request) ? MEMBERS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/members")
  public String addMember(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @Valid @ModelAttribute(MEMBER_FORM_ATTRIBUTE) final AddWorkspaceMemberForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);
    if (bindingResult.hasErrors()) {
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
      // ADR-0028: the full (non-HTMX) page render below also includes the Teams section, which
      // needs its own data populated too — omitting this on an error-redisplay path (as opposed
      // to a successful mutation, which always redirects to a fresh GET) left "workspace"/"teams"
      // unset here, a real bug caught by this controller's own test suite (a null workspace.name()
      // SpelEvaluationException on the very next render).
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? MEMBERS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    try {
      addMemberUseCase.handle(
          new AddWorkspaceMemberCommand(
              workspaceId,
              form.getEmail(),
              form.getRoleId(),
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceNotFoundException | WorkspaceRoleNotFoundException _) {
      // Not expected on this path — requireOwnedWorkspace above already confirmed workspaceId
      // exists, and the form's own role selector only ever offers this Organization's real
      // roles — but a loud 404 is still safer than assuming either guarantee can never race
      // with a concurrent deletion (this module's own future increments may add one).
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final AccountProvisioner.AccountAlreadyExistsException _) {
      model.addAttribute("emailAlreadyRegisteredError", true);
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, form);
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
      // ADR-0028: the full (non-HTMX) page render below also includes the Teams section, which
      // needs its own data populated too — omitting this on an error-redisplay path (as opposed
      // to a successful mutation, which always redirects to a fresh GET) left "workspace"/"teams"
      // unset here, a real bug caught by this controller's own test suite (a null workspace.name()
      // SpelEvaluationException on the very next render).
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? MEMBERS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterMutation(request, organization, workspace, model);
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/members/{accountId}/role")
  public String changeRole(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID accountId,
      // ADR-0027: nullable — an absent/blank value unassigns the member's role entirely (§5),
      // an explicitly allowed state, not a validation error.
      @RequestParam(required = false) final UUID newRoleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);

    try {
      changeMemberRole.handle(
          new ChangeWorkspaceMemberRoleCommand(
              workspaceId,
              accountId,
              newRoleId,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceMembershipNotFoundException | WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDemoteLastAdminException _) {
      model.addAttribute("cannotDemoteLastAdminError", true);
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
      // ADR-0028: the full (non-HTMX) page render below also includes the Teams section, which
      // needs its own data populated too — omitting this on an error-redisplay path (as opposed
      // to a successful mutation, which always redirects to a fresh GET) left "workspace"/"teams"
      // unset here, a real bug caught by this controller's own test suite (a null workspace.name()
      // SpelEvaluationException on the very next render).
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? MEMBERS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterMutation(request, organization, workspace, model);
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/members/{accountId}/remove")
  public String removeMember(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID accountId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);

    try {
      removeMemberUseCase.handle(
          new RemoveWorkspaceMemberCommand(
              workspaceId, accountId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceMembershipNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotRemoveLastAdminException _) {
      model.addAttribute("cannotRemoveLastAdminError", true);
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
      // ADR-0028: the full (non-HTMX) page render below also includes the Teams section, which
      // needs its own data populated too — omitting this on an error-redisplay path (as opposed
      // to a successful mutation, which always redirects to a fresh GET) left "workspace"/"teams"
      // unset here, a real bug caught by this controller's own test suite (a null workspace.name()
      // SpelEvaluationException on the very next render).
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? MEMBERS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterMutation(request, organization, workspace, model);
  }

  // ADR-0028: the Workspace-detail page's own Teams section — create/rename/delete a team, and
  // add/remove one of this Organization's own roles to/from it. Every write here goes through the
  // exact same use cases a future REST admin API would (none exists yet for Teams — dashboard-only
  // in this increment, same "no permission semantics of its own" posture the ADR's own §4/open-
  // question-3 documents).
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
      // ADR-0028: the full (non-HTMX) page render below also includes the Members section, which
      // needs its own data populated too — same "each section's own populate* method is only
      // guaranteed to run on its own action, never assume the other one already did" lesson the
      // member-action handlers' own identical fix above already documents, mirrored here.
      // MEMBER_FORM_ATTRIBUTE isn't set by populateMembersModel itself (every caller sets it
      // explicitly, same established convention) — a fresh blank one, since this action never
      // touches the member-add form's own state.
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
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
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
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
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
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
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
      populateTeamsModel(model, workspace);
      return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  @PostMapping("/{workspaceId}/teams/{teamId}/roles/{roleId}/remove")
  public String removeRoleFromTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      @PathVariable final UUID roleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = requireOwnedWorkspace(organizationId, workspaceId);

    try {
      removeRoleFromTeamUseCase.handle(
          new RemoveRoleFromWorkspaceTeamCommand(
              workspaceId, teamId, roleId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    return afterTeamMutation(request, organization, workspace, model);
  }

  // Shared "a team mutation just succeeded" tail — same rationale as afterMutation's own
  // identical shape for member actions, just targeting the Teams section instead.
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

  // Shared "a mutation just succeeded" tail: HTMX gets the members fragment re-rendered in place
  // (200), a fresh blank memberForm exactly like a real GET would produce; a plain form submit
  // gets a full redirect: back to this same page — same rationale
  // PlatformOrganizationDashboardController's own create() already documents for its identical
  // shape.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String afterMutation(
      final HttpServletRequest request,
      final Organization organization,
      final Workspace workspace,
      final Model model) {
    if (isHtmxRequest(request)) {
      model.addAttribute(MEMBER_FORM_ATTRIBUTE, new AddWorkspaceMemberForm());
      populateMembersModel(model, organization, workspace, KeysetPageRequest.first());
      return MEMBERS_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX + organization.id() + "/workspaces/" + workspace.id();
  }

  private void populateMembersModel(
      final Model model,
      final Organization organization,
      final Workspace workspace,
      final KeysetPageRequest pageRequest) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    model.addAttribute("workspace", workspace);
    // ADR-0027: keyed by id — the member-add/role-change forms' own role selector iterates
    // roles.values(), and the member table's own role badge resolves a member's roleId straight
    // to its WorkspaceRole (or null, gracefully, for an unassigned/deleted-role member) via
    // roles.get(...) — one attribute serving both needs. Every WorkspaceRole defined for this
    // Workspace's own Organization, not just this one Workspace's assigned subset (roles are
    // Organization-scoped, shared across every Workspace it owns).
    final Map<UUID, WorkspaceRole> rolesById =
        listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organization.id())).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));
    model.addAttribute("roles", rolesById);
    final KeysetPage<WorkspaceMembership> membersPage =
        listMembers.handle(new ListWorkspaceMembersPagedQuery(workspace.id(), pageRequest));
    model.addAttribute("members", membersPage.content());
    model.addAttribute("membersPage", membersPage);
  }

  // ADR-0028: teams (List<WorkspaceTeam>), the roles grouped into each one (Map<UUID, List
  // <WorkspaceRole>>, keyed by teamId), and every Organization role with no team association in
  // this Workspace ("ungrouped"). Reloads this Organization's own role catalog independently of
  // populateMembersModel's own identical-shaped lookup — a small, deliberate redundant read (this
  // is a low-traffic admin dashboard) rather than threading a shared map between two otherwise-
  // independent model-population methods.
  private void populateTeamsModel(final Model model, final Workspace workspace) {
    // Self-sufficient on "workspace" (unlike "organization", which every call site already sets
    // independently) — several call sites (the Team-mutation error/success paths) call this
    // method without ever calling populateMembersModel, which is otherwise the only place that
    // attribute gets set; a missing "workspace" here is a null workspace.name()
    // SpelEvaluationException on the very next render, not a graceful no-op.
    model.addAttribute("workspace", workspace);
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

    model.addAttribute("teams", teams);
    model.addAttribute("teamRoles", rolesByTeamId);
    model.addAttribute("ungroupedRoles", ungroupedRoles);
    model.addAttribute(CREATE_TEAM_FORM_ATTRIBUTE, new CreateWorkspaceTeamForm());
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
