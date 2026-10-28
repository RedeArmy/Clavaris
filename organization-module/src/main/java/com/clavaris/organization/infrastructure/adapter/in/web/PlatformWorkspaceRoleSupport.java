package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the two {@code WorkspaceRole} dashboard controllers share (TD-ARCH-042): resolving an
 * Organization the signed-in {@code PlatformAccount} really owns (a miss is a 404, same
 * anti-enumeration posture as {@link PlatformWorkspaceController}), loading that Organization's
 * roles, and the model the list, create and detail pages all render. Split out when {@code
 * PlatformWorkspaceRoleController} was divided into list/create and detail, so each keeps only the
 * use cases it calls.
 */
// java:S1075: ORGANIZATIONS_PATH_PREFIX/ORGANIZATIONS_REDIRECT_PREFIX are routes this server-
// rendered app owns and serves itself, not an external URI a deployment should be able to repoint
// — same reasoning PlatformWorkspaceController's WORKSPACES_PATH_SEGMENT suppression documents.
// PMD.TooManyMethods: small single-purpose helpers both controllers call, which is why they live
// here instead of being duplicated.
@SuppressWarnings({"java:S1075", "PMD.TooManyMethods"})
@Component
class PlatformWorkspaceRoleSupport {

  /* package */ static final String ORGANIZATION_ATTRIBUTE = "organization";
  /* package */ static final String ROLE_ATTRIBUTE = "role";
  /* package */ static final String ORGANIZATIONS_REDIRECT_PREFIX =
      "redirect:/platform/dashboard/organizations/";

  private static final String ROLES_ATTRIBUTE = "roles";
  private static final String ROLES_BY_ID_ATTRIBUTE = "rolesById";
  private static final String BACK_TARGET_ATTRIBUTE = "backTarget";
  private static final String BACK_LABEL_ATTRIBUTE = "backLabel";
  private static final String ORGANIZATIONS_PATH_PREFIX = "/platform/dashboard/organizations/";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  /* package */ PlatformWorkspaceRoleSupport(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.listRoles = listRoles;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  /* package */ Organization requireOwnedOrganization(
      final HttpServletRequest request, final UUID organizationId) {
    return requireOwnedOrganization(organizationId, requireCurrentPlatformAccount(request));
  }

  /* package */ Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Same rationale as PlatformWorkspaceController's own identical method.
  /* package */ UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }

  /* package */ Map<UUID, WorkspaceRole> loadRolesById(final UUID organizationId) {
    return listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId)).stream()
        .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));
  }

  /* package */ void populateRolesModel(final Model model, final UUID organizationId) {
    populateRolesModel(model, loadRolesById(organizationId));
  }

  /* package */ void populateRolesModel(
      final Model model, final Map<UUID, WorkspaceRole> rolesById) {
    final List<WorkspaceRole> sortedRoles =
        rolesById.values().stream().sorted(Comparator.comparing(WorkspaceRole::name)).toList();
    model.addAttribute(ROLES_ATTRIBUTE, sortedRoles);
    model.addAttribute(ROLES_BY_ID_ATTRIBUTE, rolesById);
    addPermissionSuggestions(model, sortedRoles);
  }

  // Suggestions for the Permissions picker: Clavaris keeps no catalog of consumer-defined
  // permissions (ADR-0027 §1), so what it can offer is its own reserved permissions plus every
  // permission any role of this Organization already carries — sorted, distinct. A future
  // per-Organization permission catalog replaces only this method.
  private static void addPermissionSuggestions(
      final Model model, final List<WorkspaceRole> organizationRoles) {
    final List<String> usedPermissions =
        organizationRoles.stream()
            .flatMap(role -> role.permissions().stream())
            .filter(permission -> !ReservedWorkspacePermissions.ALL.contains(permission))
            .distinct()
            .sorted()
            .toList();
    model.addAttribute(
        "clavarisPermissions", ReservedWorkspacePermissions.ALL.stream().sorted().toList());
    model.addAttribute("usedPermissions", usedPermissions);
  }

  // Live UX request, 2026-10-02: Back/Cancel on the create and detail pages return to wherever the
  // operator came from — Teams & Roles' own "Advanced create"/"Modify" links (workspace-
  // detail.html) thread their own workspaceId through (a query param on GET, a hidden form field on
  // POST so it survives a validation-error redisplay) specifically so this works; absent, the
  // default is the Workspace Roles list. Deliberately not validated against this Organization's
  // own real Workspaces — the worst a tampered workspaceId can do is link to a 404
  // (PlatformWorkspaceController's own requireOwnedWorkspace re-validates independently when that
  // link is actually followed), never an open redirect — the target is always built from this
  // fixed, same-origin path template, never from an arbitrary caller-supplied URL.
  /* package */ void populateBackTarget(
      final Model model, final UUID organizationId, final UUID workspaceId) {
    // Exposed as its own attribute too — the create/detail forms thread it back through as a
    // hidden field so it survives a validation-error redisplay (a GET query param alone wouldn't
    // survive the POST).
    model.addAttribute("workspaceId", workspaceId);
    if (workspaceId != null) {
      model.addAttribute(
          BACK_TARGET_ATTRIBUTE,
          ORGANIZATIONS_PATH_PREFIX + organizationId + "/workspaces/" + workspaceId);
      model.addAttribute(BACK_LABEL_ATTRIBUTE, "Back to Teams & Roles");
    } else {
      model.addAttribute(
          BACK_TARGET_ATTRIBUTE, ORGANIZATIONS_PATH_PREFIX + organizationId + "/workspace-roles");
      model.addAttribute(BACK_LABEL_ATTRIBUTE, "Back to Workspace Roles");
    }
  }

  /* package */ String redirectToList(final UUID organizationId) {
    return ORGANIZATIONS_REDIRECT_PREFIX + organizationId + "/workspace-roles";
  }

  // Newline-separated, one opaque permission string per line (opaque, consumer-defined strings —
  // WorkspaceRole's own Javadoc). A null input degrades to the empty string rather than an early
  // return: splitting it still yields one blank element, filtered out below the same way a blank
  // line typed in the textarea already is — one exit point, no separate null/blank branch needed.
  /* package */ static Set<String> parsePermissions(final String permissionsText) {
    final String text = permissionsText == null ? "" : permissionsText;
    return Arrays.stream(text.split("\\R"))
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /* package */ static String joinPermissions(final Set<String> permissions) {
    return permissions.stream().sorted().collect(Collectors.joining("\n"));
  }
}
