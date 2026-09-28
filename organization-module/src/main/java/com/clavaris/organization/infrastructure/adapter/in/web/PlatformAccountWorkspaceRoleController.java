package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountCommand;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountQuery;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationQuery;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
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
import java.util.ArrayList;
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
 * the network hop (ADR-0029 §3).
 *
 * <p>SDE-III redesign, 2026-09-27 (live UX request, "Way 2"): rebuilt on {@link
 * AssignWorkspaceRoleToAccountUseCase} instead of {@link
 * com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase}
 * — the popup can now reach an account with no {@link WorkspaceMembership} at all (previously a
 * 404, "This account isn't a member of any Workspace yet" with no way forward), originating a
 * brand-new membership rather than only ever reassigning an existing one. When a membership already
 * exists, its own {@code workspaceId} stays fixed (no way to move an existing member to a different
 * Workspace through this popup — a deliberately narrower scope than "assign," kept out to avoid an
 * unrequested, unexplored capability). When one doesn't exist yet, a Workspace selector appears —
 * hidden and auto-selected when the Organization has exactly one Workspace (or none: an empty state
 * instead of a form), shown only when there's a genuine choice; changing it reloads this whole
 * fragment (a coarser context switch than Team/Role, which stay client-side — see {@code
 * assign-role-picker.js}) via its own {@code hx-get} so Team/Role reflect the newly chosen
 * Workspace's own real data.
 *
 * <p>The Team selector (real, workspace-scoped teams, plus the synthetic "No team"/ungrouped
 * bucket) only counts/shows a team that actually has at least one role in it — an empty team
 * contributes nothing to pick from, so it's excluded entirely, not just from the options but from
 * the "how many choices are there" count that decides whether to show the selector at all. If the
 * total qualifying buckets (teams-with-roles + "No team" if it has any) is one or none, the
 * selector doesn't render — whichever single bucket exists (if any) is used directly.
 *
 * <p>Distinct from {@link PlatformWorkspaceController}: this controller's resource is keyed by
 * {@code accountId}, not {@code workspaceId} — the caller doesn't know in advance which Workspace
 * (if any) the account belongs to, which is exactly what {@link
 * FindWorkspaceMembershipForAccountUseCase} resolves first.
 */
// PMD.LongVariable: several fragment-name constants and currentPlatformAccount/
// ownerPlatformAccountId are the same, deliberately descriptive names PlatformWorkspaceController
// already uses throughout — same class-level suppression that class already carries.
// PMD.ExcessiveImports: every import backs a real, distinct collaborator or exception this
// controller genuinely needs — wiring, not sprawl, same reasoning documented on every other
// growing controller in this codebase.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports", "PMD.TooManyMethods"})
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
  private final ListWorkspacesForOrganizationUseCase listWorkspaces;
  private final ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final AssignWorkspaceRoleToAccountUseCase assignRoleToAccount;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // PlatformWorkspaceController's own identical constructor.
  public PlatformAccountWorkspaceRoleController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetWorkspaceForOrganizationUseCase getWorkspace,
      final FindWorkspaceMembershipForAccountUseCase findMembership,
      final ListWorkspacesForOrganizationUseCase listWorkspaces,
      final ListWorkspaceTeamsForWorkspaceUseCase listTeams,
      final ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final AssignWorkspaceRoleToAccountUseCase assignRoleToAccount,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getWorkspace = getWorkspace;
    this.findMembership = findMembership;
    this.listWorkspaces = listWorkspaces;
    this.listTeams = listTeams;
    this.listTeamRoleIds = listTeamRoleIds;
    this.listRoles = listRoles;
    this.assignRoleToAccount = assignRoleToAccount;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showForm(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam(required = false) final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);

    final Optional<WorkspaceMembership> membership =
        findMembership.handle(new FindWorkspaceMembershipForAccountQuery(accountId));
    // SDE-III review, 2026-09-27: findMembership resolves by accountId alone (no organizationId
    // filter, see that use case's own Javadoc) — without this check, a membership belonging to an
    // Organization the caller doesn't own would still populate the model below with that other
    // Organization's own team names/ids (real cross-tenant disclosure, not hypothetical: the
    // fragment renders every team's th:text/data-team-id).
    membership.ifPresent(m -> requireOwnedWorkspace(organizationId, m.workspaceId()));
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("accountId", accountId);
    model.addAttribute("membership", membership.orElse(null));

    if (membership.isPresent()) {
      populateRoleOptions(
          model, organizationId, membership.get().workspaceId(), membership.get().roleId());
      return ASSIGN_ROLE_FORM_FRAGMENT;
    }

    final List<Workspace> allWorkspaces =
        listWorkspaces.handle(new ListWorkspacesForOrganizationQuery(organizationId));
    if (allWorkspaces.isEmpty()) {
      model.addAttribute("noWorkspacesYet", true);
      return ASSIGN_ROLE_FORM_FRAGMENT;
    }

    final UUID activeWorkspaceId = resolveActiveWorkspaceId(allWorkspaces, workspaceId);
    model.addAttribute("workspaces", allWorkspaces);
    model.addAttribute("showWorkspaceSelector", allWorkspaces.size() > 1);
    model.addAttribute("activeWorkspaceId", activeWorkspaceId);
    populateRoleOptions(model, organizationId, activeWorkspaceId, null);
    return ASSIGN_ROLE_FORM_FRAGMENT;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String save(
      final HttpServletRequest request,
      final HttpServletResponse response,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam final UUID workspaceId,
      @RequestParam final UUID newRoleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    requireOwnedWorkspace(organizationId, workspaceId);

    final Optional<WorkspaceMembership> membership =
        findMembership.handle(new FindWorkspaceMembershipForAccountQuery(accountId));
    // Defense in depth — same anti-enumeration posture showForm's own identical check documents.
    membership.ifPresent(m -> requireOwnedWorkspace(organizationId, m.workspaceId()));
    // The form's own Workspace field is a hidden, fixed value whenever a membership already
    // exists (see this class's own Javadoc — moving an existing member to a different Workspace
    // isn't a capability this popup offers) — a mismatch here means the submitted value was
    // tampered with, not a real user choice.
    if (membership.isPresent() && !membership.get().workspaceId().equals(workspaceId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    try {
      assignRoleToAccount.handle(
          new AssignWorkspaceRoleToAccountCommand(
              workspaceId,
              accountId,
              newRoleId,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceNotFoundException
        | WorkspaceRoleNotFoundException
        | AccountNotInOrganizationException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDemoteLastAdminException _) {
      model.addAttribute("organizationId", organizationId);
      model.addAttribute("accountId", accountId);
      model.addAttribute("membership", membership.orElse(null));
      model.addAttribute("cannotDemoteLastAdminError", true);
      if (membership.isPresent()) {
        populateRoleOptions(
            model, organizationId, membership.get().workspaceId(), membership.get().roleId());
      } else {
        final List<Workspace> allWorkspaces =
            listWorkspaces.handle(new ListWorkspacesForOrganizationQuery(organizationId));
        model.addAttribute("workspaces", allWorkspaces);
        model.addAttribute("showWorkspaceSelector", allWorkspaces.size() > 1);
        model.addAttribute("activeWorkspaceId", workspaceId);
        populateRoleOptions(model, organizationId, workspaceId, null);
      }
      return ASSIGN_ROLE_FORM_FRAGMENT;
    }

    response.setHeader("HX-Trigger", ROLE_ASSIGNED_EVENT);
    return ASSIGN_ROLE_SAVED_FRAGMENT;
  }

  // A submitted workspaceId not among this Organization's own real Workspaces is tampering (the
  // Workspace <select> only ever offers this list) — falls back to the first real Workspace
  // instead, same "never trust a client value blindly" posture the rest of this controller holds
  // to elsewhere.
  private static UUID resolveActiveWorkspaceId(
      final List<Workspace> allWorkspaces, final UUID requestedWorkspaceId) {
    if (requestedWorkspaceId != null) {
      final boolean valid =
          allWorkspaces.stream().anyMatch(workspace -> workspace.id().equals(requestedWorkspaceId));
      if (valid) {
        return requestedWorkspaceId;
      }
    }
    return allWorkspaces.get(0).id();
  }

  // Same "teams, the roles grouped into each one, and every ungrouped role" shape
  // PlatformWorkspaceController's own populateTeamsModel already establishes (ADR-0028), plus this
  // class's own "a team with zero roles doesn't count" filter (see this class's own Javadoc) —
  // teams here is a strict subset of ListWorkspaceTeamsForWorkspaceUseCase's own result.
  private void populateRoleOptions(
      final Model model,
      final UUID organizationId,
      final UUID workspaceId,
      final UUID currentRoleId) {
    final List<WorkspaceRole> allRoles =
        listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId));
    final List<WorkspaceTeam> allTeams =
        listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspaceId));

    final Map<UUID, UUID> roleTeamId = new HashMap<>();
    final List<WorkspaceTeam> teamsWithRoles = new ArrayList<>();
    for (final WorkspaceTeam team : allTeams) {
      final List<UUID> teamRoleIds =
          listTeamRoleIds.handle(new ListWorkspaceTeamRoleIdsQuery(team.id()));
      if (teamRoleIds.isEmpty()) {
        continue;
      }
      teamsWithRoles.add(team);
      for (final UUID roleId : teamRoleIds) {
        roleTeamId.put(roleId, team.id());
      }
    }

    final boolean hasUngroupedRoles =
        allRoles.stream().anyMatch(role -> !roleTeamId.containsKey(role.id()));
    final int bucketCount = teamsWithRoles.size() + (hasUngroupedRoles ? 1 : 0);

    model.addAttribute("teams", teamsWithRoles);
    model.addAttribute("allRoles", allRoles);
    model.addAttribute("roleTeamId", roleTeamId);
    model.addAttribute("currentRoleId", currentRoleId);
    model.addAttribute("showTeamSelector", bucketCount > 1);
    model.addAttribute("hasAnyRoles", bucketCount > 0);
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
