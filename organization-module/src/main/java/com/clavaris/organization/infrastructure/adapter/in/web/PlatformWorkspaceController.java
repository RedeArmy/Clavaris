package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
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
import com.clavaris.organization.application.usecase.removeworkspacemember.CannotRemoveLastAdminException;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberCommand;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
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
  private static final String MEMBER_FORM_ATTRIBUTE = "memberForm";
  private static final String ORGANIZATION_ATTRIBUTE = "organization";

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

  // java:S107/PMD.ExcessiveParameterList: one parameter per collaborating port — same rationale
  // as every other multi-collaborator constructor in this codebase; ADR-0027's own
  // listRoles addition pushed this past PMD's default threshold (10), not a new design smell.
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
      final CurrentPlatformAccountResolver currentPlatformAccount) {
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
    return "redirect:/platform/dashboard/organizations/" + organizationId;
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
      return isHtmxRequest(request) ? MEMBERS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
    }

    return afterMutation(request, organization, workspace, model);
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
    return "redirect:/platform/dashboard/organizations/"
        + organization.id()
        + "/workspaces/"
        + workspace.id();
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
