package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationQuery;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * What every Workspace dashboard controller needs before it does anything: who is calling, and
 * proof that the Organization, Workspace or role in the URL belongs to them. Anything that fails
 * the check 404s identically to "does not exist" (anti-enumeration; {@code roleId} is resolved from
 * the Organization's own role list first because the delete/update commands carry no {@code
 * organizationId} of their own — see {@link PlatformWorkspaceRoleController}). Also the home of the
 * constants those controllers share.
 */
@Component
// PMD.LongVariable: the shared constants are named for what they are.
@SuppressWarnings({"java:S1075", "PMD.LongVariable"})
final class WorkspaceDashboardSupport {

  /* default */ static final String ORGANIZATION_ATTRIBUTE = "organization";
  /* default */ static final String ORGANIZATIONS_REDIRECT_PREFIX =
      "redirect:/platform/dashboard/organizations/";
  // java:S1075: a route this server-rendered app owns and serves itself, not an external URI a
  // deployment should be able to repoint.
  /* default */ static final String WORKSPACES_PATH_SEGMENT = "/workspaces/";
  /* default */ static final String CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE =
      "cannotDemoteLastAdminError";
  // Live UX request, 2026-09-29: the literal word an operator must type into the "this would
  // leave nobody able to manage members/roles" confirmation popup before the dashboard bypasses
  // ManageMembersGuard — see ChangeWorkspaceMemberRoleCommand#force()'s own Javadoc for why this
  // override is safe here (dashboard-only, never REST-reachable) but not elsewhere.
  /* default */ static final String FORCE_CONFIRMATION_WORD = "unassign";

  // HTMX's own request header (https://htmx.org/reference/#request_headers).
  private static final String HX_REQUEST_HEADER = "HX-Request";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetWorkspaceForOrganizationUseCase getWorkspace;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  /* default */ WorkspaceDashboardSupport(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetWorkspaceForOrganizationUseCase getWorkspace,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getWorkspace = getWorkspace;
    this.listRoles = listRoles;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  /* default */ Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /* default */ Workspace requireOwnedWorkspace(final UUID organizationId, final UUID workspaceId) {
    return getWorkspace
        .handle(new GetWorkspaceForOrganizationQuery(organizationId, workspaceId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Same anti-enumeration posture PlatformWorkspaceRoleController's own requireOwnedRole
  // establishes for the identical DeleteWorkspaceRoleCommand/UpdateWorkspaceRoleCommand shape
  // (neither carries its own organizationId) — a roleId belonging to a different Organization 404s
  // here before DeleteWorkspaceRoleUseCase is ever called.
  /* default */ void requireOwnedRole(final UUID organizationId, final UUID roleId) {
    final boolean owned =
        listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId)).stream()
            .anyMatch(role -> role.id().equals(roleId));
    if (!owned) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /* default */ static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }

  // Same rationale as PlatformOrganizationDashboardController's own identical method.
  /* default */ UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
