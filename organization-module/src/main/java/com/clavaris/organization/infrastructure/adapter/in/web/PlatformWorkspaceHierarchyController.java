package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.FORCE_CONFIRMATION_WORD;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.ORGANIZATION_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.isHtmxRequest;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import jakarta.servlet.http.HttpServletRequest;
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
 * The Workspace's read-mostly Team &rarr; Role &rarr; Members tab, and the "Remove" action on a
 * member. A real GET route, not an HTMX-only fragment, so each tab stays bookmarkable and works
 * without JavaScript (same convention as {@code org-tabs.html}); it also doubles as the
 * self-refresh target the Assign popups ({@link PlatformWorkspaceAssignRoleController}) trigger on
 * success. See {@link PlatformWorkspaceController} for the family this belongs to and the shared
 * ownership/HTMX conventions.
 */
// PMD.LongVariable: collaborator names spell out the use case they hold.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspaces")
public class PlatformWorkspaceHierarchyController {

  private static final String TEAMS_HIERARCHY_VIEW =
      "organization/platform/workspace-teams-hierarchy";
  private static final String TEAMS_HIERARCHY_FRAGMENT = TEAMS_HIERARCHY_VIEW + " :: hierarchy";

  private final WorkspaceDashboardSupport access;
  private final WorkspaceHierarchyModel hierarchyModel;
  private final ChangeWorkspaceMemberRoleUseCase changeMemberRoleUseCase;

  public PlatformWorkspaceHierarchyController(
      final WorkspaceDashboardSupport access,
      final WorkspaceHierarchyModel hierarchyModel,
      final ChangeWorkspaceMemberRoleUseCase changeMemberRoleUseCase) {
    this.access = access;
    this.hierarchyModel = hierarchyModel;
    this.changeMemberRoleUseCase = changeMemberRoleUseCase;
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
      @RequestParam(required = false) final String after,
      @RequestParam(required = false) final String before,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    hierarchyModel.populateTeamsHierarchyModel(
        model, workspace, KeysetPageRequest.fromCursors(after, before));
    return isHtmxRequest(request) ? TEAMS_HIERARCHY_FRAGMENT : TEAMS_HIERARCHY_VIEW;
  }

  // Live UX request, 2026-09-28: "Remove" next to each member in the Teams hierarchy — unassigns
  // just that one member's role (composes the existing ChangeWorkspaceMemberRoleUseCase with
  // newRoleId=null, ADR-0027 §5's own explicitly-allowed "roleless member" state), the account and
  // its WorkspaceMembership both survive untouched.
  //
  // Live UX request, 2026-09-29: the Clavaris platform dashboard is a strictly higher trust tier
  // than the ManageMembersGuard invariant this used to unconditionally enforce — see
  // ChangeWorkspaceMemberRoleCommand#force()'s own Javadoc for the full reasoning. First attempt
  // (confirmedAction absent) runs guarded, same as before; if it hits
  // CannotDemoteLastAdminException,
  // this re-renders with that same row now showing a "type UNASSIGN to confirm" inline form instead
  // of the plain Remove button — submitting THAT retries with force=true.
  @PostMapping("/{workspaceId}/members/{accountId}/unassign-role")
  public String unassignMemberFromRole(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID accountId,
      @RequestParam(required = false) final String confirmedAction,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    final boolean force = FORCE_CONFIRMATION_WORD.equals(confirmedAction);

    try {
      changeMemberRoleUseCase.handle(
          new ChangeWorkspaceMemberRoleCommand(
              workspaceId,
              accountId,
              null,
              force,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceMembershipNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDemoteLastAdminException _) {
      model.addAttribute(CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE, true);
      model.addAttribute("forceConfirmAccountId", accountId);
    }

    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    // Resets to the first page — same "a mutation refresh starts over, not mid-list" convention
    // this dashboard's other search/filter-affecting actions already establish.
    hierarchyModel.populateTeamsHierarchyModel(model, workspace, KeysetPageRequest.first());
    return isHtmxRequest(request) ? TEAMS_HIERARCHY_FRAGMENT : TEAMS_HIERARCHY_VIEW;
  }
}
