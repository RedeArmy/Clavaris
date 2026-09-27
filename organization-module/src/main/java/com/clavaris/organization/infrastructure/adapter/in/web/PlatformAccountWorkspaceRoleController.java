package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountQuery;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationQuery;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * ADR-0029: serves the "Assign role" popup identity-module's {@code organization-users.html} (Users
 * tab) opens for an Account, without giving identity-module a Maven dependency on this module — the
 * dialog's body is loaded via a plain HTMX {@code hx-get}/{@code hx-post} against this controller's
 * own URLs, the same way any two independently-deployable services would compose a UI, just without
 * the network hop (ADR-0029 §3). The popup can never bypass {@link
 * ChangeWorkspaceMemberRoleUseCase}'s own invariants — it is the exact same use case ADR-0027/0028
 * already built and tested, called from a second entry point, never a parallel write path.
 *
 * <p>Distinct from {@link PlatformWorkspaceController}: this controller's resource is keyed by
 * {@code accountId}, not {@code workspaceId} — the caller doesn't know in advance which Workspace
 * (if any) the account belongs to, which is exactly what {@link
 * FindWorkspaceMembershipForAccountUseCase} resolves first.
 */
// PMD.LongVariable: several fragment-name constants and currentPlatformAccount/
// ownerPlatformAccountId are the same, deliberately descriptive names PlatformWorkspaceController
// already uses throughout — same class-level suppression that class already carries.
// PMD.ExcessiveImports: eight collaborators plus their commands/exceptions across three use-case
// packages — wiring, not sprawl, same reasoning documented on every other growing controller in
// this codebase.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
@Controller
@RequestMapping(
    "/platform/dashboard/organizations/{organizationId}/accounts/{accountId}/assign-role")
public class PlatformAccountWorkspaceRoleController {

  private static final String ASSIGN_ROLE_FORM_FRAGMENT =
      "organization/platform/fragments/assign-role-form :: assignRoleForm";
  private static final String ASSIGN_ROLE_SAVED_FRAGMENT =
      "organization/platform/fragments/assign-role-form :: assignRoleSaved";

  // htmx's own response-header convention: any DOM event named here fires on document.body once
  // the swap completes — identity-module's Users-tab list container listens for this same name
  // (hx-trigger="workspace-role-assigned from:body") to refresh its own Role column, and
  // organization-dialog.js listens for it too, to auto-close the dialog on a successful save. No
  // Java-level coupling either way — both sides only agree on this one string.
  private static final String ROLE_ASSIGNED_EVENT = "workspace-role-assigned";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetWorkspaceForOrganizationUseCase getWorkspace;
  private final FindWorkspaceMembershipForAccountUseCase findMembership;
  private final ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final ChangeWorkspaceMemberRoleUseCase changeMemberRole;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // PlatformWorkspaceController's own identical constructor.
  public PlatformAccountWorkspaceRoleController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetWorkspaceForOrganizationUseCase getWorkspace,
      final FindWorkspaceMembershipForAccountUseCase findMembership,
      final ListWorkspaceTeamsForWorkspaceUseCase listTeams,
      final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final ChangeWorkspaceMemberRoleUseCase changeMemberRole,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getWorkspace = getWorkspace;
    this.findMembership = findMembership;
    this.listTeams = listTeams;
    this.listTeamRoleIds = listTeamRoleIds;
    this.listRoles = listRoles;
    this.changeMemberRole = changeMemberRole;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showForm(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    final Optional<WorkspaceMembership> membership =
        findMembership.handle(new FindWorkspaceMembershipForAccountQuery(accountId));
    // SDE-III review, 2026-09-27: findMembership resolves by accountId alone (no organizationId
    // filter, see that use case's own Javadoc) — without this check, a membership belonging to an
    // Organization the caller doesn't own would still populate the model below with that other
    // Organization's own team names/ids (real cross-tenant disclosure, not hypothetical: the
    // fragment renders every team's th:text/data-team-id). save() below already had this exact
    // check; showForm() didn't, an inconsistency between the two handlers of the same resource,
    // not a deliberate difference — same anti-enumeration posture every other dashboard controller
    // in this codebase already holds itself to.
    membership.ifPresent(m -> requireOwnedWorkspace(organizationId, m.workspaceId()));
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("accountId", accountId);
    model.addAttribute("membership", membership.orElse(null));
    if (membership.isPresent()) {
      populateRoleOptions(model, organizationId, membership.get());
    }
    return ASSIGN_ROLE_FORM_FRAGMENT;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String save(
      final HttpServletRequest request,
      final HttpServletResponse response,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam final UUID newRoleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    final WorkspaceMembership membership =
        findMembership
            .handle(new FindWorkspaceMembershipForAccountQuery(accountId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    // Defense in depth — requireOwnedWorkspace also confirms this membership's Workspace belongs
    // to the Organization the current PlatformAccount actually owns, same anti-enumeration posture
    // PlatformWorkspaceController's own identical check documents.
    requireOwnedWorkspace(organizationId, membership.workspaceId());

    try {
      changeMemberRole.handle(
          new ChangeWorkspaceMemberRoleCommand(
              membership.workspaceId(),
              accountId,
              newRoleId,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceMembershipNotFoundException | WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDemoteLastAdminException _) {
      model.addAttribute("organizationId", organizationId);
      model.addAttribute("accountId", accountId);
      model.addAttribute("membership", membership);
      model.addAttribute("cannotDemoteLastAdminError", true);
      populateRoleOptions(model, organizationId, membership);
      return ASSIGN_ROLE_FORM_FRAGMENT;
    }

    response.setHeader("HX-Trigger", ROLE_ASSIGNED_EVENT);
    return ASSIGN_ROLE_SAVED_FRAGMENT;
  }

  // Same "teams, the roles grouped into each one, and every ungrouped role" shape
  // PlatformWorkspaceController's own populateTeamsModel already establishes (ADR-0028) — reused
  // here for the popup's own Team/Role selects rather than a Teams-section page.
  private void populateRoleOptions(
      final Model model, final UUID organizationId, final WorkspaceMembership membership) {
    final List<WorkspaceRole> allRoles =
        listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId));
    final List<WorkspaceTeam> teams =
        listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(membership.workspaceId()));

    // Maps each grouped role's id to its own team id — the popup's own client-side script filters
    // the Role <select> by this, defaulting anything absent here to "ungrouped".
    final Map<UUID, UUID> roleTeamId = new HashMap<>();
    for (final WorkspaceTeam team : teams) {
      for (final UUID roleId :
          listTeamRoleIds.handle(new ListWorkspaceTeamRoleIdsQuery(team.id()))) {
        roleTeamId.put(roleId, team.id());
      }
    }

    model.addAttribute("teams", teams);
    model.addAttribute("allRoles", allRoles);
    model.addAttribute("roleTeamId", roleTeamId);
    model.addAttribute("currentRoleId", membership.roleId());
  }

  private void requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Workspace requireOwnedWorkspace(final UUID organizationId, final UUID workspaceId) {
    return getWorkspace
        .handle(new GetWorkspaceForOrganizationQuery(organizationId, workspaceId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
