package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.CannotStripReservedWorkspaceRolePermissionsException;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.WorkspaceRoleCycleException;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0027 Slice 5: the dashboard's own {@code WorkspaceRole} management — a Configure sub-page
 * (same shape as Rate Limit/Signing Keys/Webhook Endpoints) since a {@code WorkspaceRole} is
 * Organization-scoped, shared across every {@code Workspace} that Organization owns, not a single
 * Workspace's own concern. Every write here goes through the exact same use cases {@code
 * /api/v1/admin/**} already exposes ({@link CreateWorkspaceRoleUseCase}, {@link
 * UpdateWorkspaceRoleUseCase}, {@link DeleteWorkspaceRoleUseCase}) — this controller adds a second,
 * session-authenticated {@link AuditActor#platformAccount} caller, not a second implementation.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase} —
 * never a bare repository call — so an organizationId this {@code PlatformAccount} doesn't own
 * resolves identically to "doesn't exist" (a 404), same anti-enumeration posture as {@link
 * PlatformWorkspaceController}. {@link UpdateWorkspaceRoleCommand}/{@link
 * DeleteWorkspaceRoleCommand} key off {@code roleId} alone (no {@code organizationId} of their own)
 * — this controller always resolves the target role from this Organization's own {@link
 * ListWorkspaceRolesForOrganizationUseCase} result first, so a {@code roleId} belonging to a
 * different Organization 404s here before either use case is ever called.
 *
 * <p>{@code permissions} is a free-text, newline-separated field (opaque, consumer-defined strings
 * — same "generic infrastructure, not a fixed picklist" posture {@link WorkspaceRole}'s own Javadoc
 * documents), parsed by {@link #parsePermissions(String)}/rendered back by {@link
 * #joinPermissions(Set)} — unlike {@code WebhookEndpoint.subscribedEventTypes}, there's no known,
 * finite catalog to offer as checkboxes.
 *
 * <p>{@link WorkspaceRoleStillAssignedException} is a real, owner-reachable outcome (deleting a
 * role members still hold) — re-rendered as a detail-page error, not redirected, same treatment
 * {@link PlatformWorkspaceController#changeRole}'s own {@code CannotDemoteLastAdminException} catch
 * already establishes. {@link CannotDeleteReservedWorkspaceRoleException} is not reachable through
 * this UI (the detail page never renders a delete control for a reserved role) — a loud 409 is
 * safer than assuming that can never race with a concurrent tamper of the submitted form.
 */
// PMD.ExcessiveImports/PMD.CouplingBetweenObjects: every import backs a real, distinct collaborator
// or exception this controller genuinely needs — same "wiring, not sprawl" reasoning this
// codebase's own comparable controllers already document.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports", "PMD.TooManyMethods"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspace-roles")
public class PlatformWorkspaceRoleController {

  private static final String LIST_VIEW = "organization/platform/workspace-roles";
  private static final String CREATE_VIEW = "organization/platform/create-workspace-role";
  private static final String DETAIL_VIEW = "organization/platform/workspace-role-detail";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";
  private static final String EDIT_FORM_ATTRIBUTE = "editForm";
  private static final String ORGANIZATION_ATTRIBUTE = "organization";
  private static final String ROLES_ATTRIBUTE = "roles";
  private static final String ROLES_BY_ID_ATTRIBUTE = "rolesById";
  private static final String ROLE_ATTRIBUTE = "role";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CreateWorkspaceRoleUseCase createRole;
  private final UpdateWorkspaceRoleUseCase updateRole;
  private final DeleteWorkspaceRoleUseCase deleteRole;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  // java:S107: one parameter per collaborating port — same rationale as every other
  // multi-collaborator constructor in this codebase.
  @SuppressWarnings("java:S107")
  public PlatformWorkspaceRoleController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CreateWorkspaceRoleUseCase createRole,
      final UpdateWorkspaceRoleUseCase updateRole,
      final DeleteWorkspaceRoleUseCase deleteRole,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.listRoles = listRoles;
    this.createRole = createRole;
    this.updateRole = updateRole;
    this.deleteRole = deleteRole;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateRolesModel(model, organizationId);
    return LIST_VIEW;
  }

  @GetMapping("/new")
  public String showCreateForm(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateRolesModel(model, organizationId);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateWorkspaceRoleForm());
    return CREATE_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final CreateWorkspaceRoleForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    if (bindingResult.hasErrors()) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateRolesModel(model, organizationId);
      return CREATE_VIEW;
    }

    try {
      createRole.handle(
          new CreateWorkspaceRoleCommand(
              organizationId,
              form.getName(),
              form.getParentRoleId(),
              parsePermissions(form.getPermissionsText()),
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — requireOwnedOrganization above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final DuplicateWorkspaceRoleNameException _) {
      model.addAttribute("duplicateNameError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateRolesModel(model, organizationId);
      return CREATE_VIEW;
    } catch (final WorkspaceRoleNotFoundException _) {
      // The parent-role dropdown only ever offers this Organization's own real roles — reaching
      // this means the submitted parentRoleId was tampered with, not a real user mistake.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    return "redirect:/platform/dashboard/organizations/" + organizationId + "/workspace-roles";
  }

  @GetMapping("/{roleId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    final Map<UUID, WorkspaceRole> rolesById = loadRolesById(organizationId);
    final WorkspaceRole role = requireOwnedRole(rolesById, roleId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateRolesModel(model, rolesById);
    model.addAttribute(ROLE_ATTRIBUTE, role);
    model.addAttribute(EDIT_FORM_ATTRIBUTE, formFor(role));
    return DETAIL_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{roleId}")
  public String update(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      @Valid @ModelAttribute(EDIT_FORM_ATTRIBUTE) final UpdateWorkspaceRoleForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Map<UUID, WorkspaceRole> rolesById = loadRolesById(organizationId);
    final WorkspaceRole existing = requireOwnedRole(rolesById, roleId);

    if (bindingResult.hasErrors()) {
      return redisplayDetail(model, organization, rolesById, existing, null);
    }

    final WorkspaceRole updated;
    try {
      updated =
          updateRole.handle(
              new UpdateWorkspaceRoleCommand(
                  roleId,
                  form.getName(),
                  form.getParentRoleId(),
                  parsePermissions(form.getPermissionsText()),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final DuplicateWorkspaceRoleNameException _) {
      return redisplayDetail(model, organization, rolesById, existing, "duplicateNameError");
    } catch (final WorkspaceRoleCycleException _) {
      return redisplayDetail(model, organization, rolesById, existing, "parentCycleError");
    } catch (final WorkspaceRoleNotFoundException _) {
      // Not expected on this path — requireOwnedRole above already confirmed roleId exists, and
      // the parent-role dropdown only ever offers this Organization's own real roles — but a loud
      // 404 is still safer than assuming either guarantee can never race with a concurrent change.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotStripReservedWorkspaceRolePermissionsException
        | IllegalArgumentException _) {
      // UpdateWorkspaceRoleService's own typed guard (ADR-0027 §2) raises the first; the rendered
      // form makes the reserved role's permissions field readonly precisely so a normal submit can
      // never reach either, but a raw/tampered POST still could. IllegalArgumentException is a
      // defense-in-depth backstop for WorkspaceRole#withPermissions's own identical (but untyped)
      // guard, never expected to trigger on its own now that the typed guard runs first.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    return "redirect:/platform/dashboard/organizations/"
        + organizationId
        + "/workspace-roles/"
        + updated.id();
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{roleId}/delete")
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      final Model model) {
    final UUID ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final Organization organization =
        requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Map<UUID, WorkspaceRole> rolesById = loadRolesById(organizationId);
    final WorkspaceRole existing = requireOwnedRole(rolesById, roleId);

    try {
      deleteRole.handle(
          new DeleteWorkspaceRoleCommand(
              roleId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDeleteReservedWorkspaceRoleException _) {
      // Not reachable through this UI — see this class's own Javadoc.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } catch (final WorkspaceRoleStillAssignedException _) {
      return redisplayDetail(model, organization, rolesById, existing, "stillAssignedError");
    } catch (final WorkspaceRoleHasChildRolesException _) {
      return redisplayDetail(model, organization, rolesById, existing, "hasChildRolesError");
    }

    return "redirect:/platform/dashboard/organizations/" + organizationId + "/workspace-roles";
  }

  private String redisplayDetail(
      final Model model,
      final Organization organization,
      final Map<UUID, WorkspaceRole> rolesById,
      final WorkspaceRole existing,
      final String errorAttribute) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateRolesModel(model, rolesById);
    model.addAttribute(ROLE_ATTRIBUTE, existing);
    if (!model.containsAttribute(EDIT_FORM_ATTRIBUTE)) {
      model.addAttribute(EDIT_FORM_ATTRIBUTE, formFor(existing));
    }
    if (errorAttribute != null) {
      model.addAttribute(errorAttribute, true);
    }
    return DETAIL_VIEW;
  }

  private static UpdateWorkspaceRoleForm formFor(final WorkspaceRole role) {
    final UpdateWorkspaceRoleForm form = new UpdateWorkspaceRoleForm();
    form.setName(role.name());
    form.setParentRoleId(role.parentRoleId());
    form.setPermissionsText(joinPermissions(role.permissions()));
    return form;
  }

  // Newline-separated, one opaque permission string per line — see this class's own Javadoc. A
  // null input degrades to the empty string rather than an early return: splitting it still
  // yields one blank element, filtered out below the same way a blank line typed in the textarea
  // already is — one exit point, no separate null/blank branch needed.
  private static Set<String> parsePermissions(final String permissionsText) {
    final String text = permissionsText == null ? "" : permissionsText;
    return Arrays.stream(text.split("\\R"))
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String joinPermissions(final Set<String> permissions) {
    return permissions.stream().sorted().collect(Collectors.joining("\n"));
  }

  private void populateRolesModel(final Model model, final UUID organizationId) {
    populateRolesModel(model, loadRolesById(organizationId));
  }

  private void populateRolesModel(final Model model, final Map<UUID, WorkspaceRole> rolesById) {
    final List<WorkspaceRole> sortedRoles =
        rolesById.values().stream().sorted(Comparator.comparing(WorkspaceRole::name)).toList();
    model.addAttribute(ROLES_ATTRIBUTE, sortedRoles);
    model.addAttribute(ROLES_BY_ID_ATTRIBUTE, rolesById);
  }

  private Map<UUID, WorkspaceRole> loadRolesById(final UUID organizationId) {
    return listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId)).stream()
        .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));
  }

  private static WorkspaceRole requireOwnedRole(
      final Map<UUID, WorkspaceRole> rolesById, final UUID roleId) {
    final WorkspaceRole role = rolesById.get(roleId);
    if (role == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return role;
  }

  private Organization requireOwnedOrganization(
      final HttpServletRequest request, final UUID organizationId) {
    return requireOwnedOrganization(organizationId, requireCurrentPlatformAccount(request));
  }

  private Organization requireOwnedOrganization(
      final UUID organizationId, final UUID ownerPlatformAccountId) {
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // Same rationale as PlatformWorkspaceController's own identical method.
  private UUID requireCurrentPlatformAccount(final HttpServletRequest request) {
    return currentPlatformAccount
        .resolve(request)
        .orElseThrow(
            () -> new IllegalStateException("No authenticated PlatformAccount on this request"));
  }
}
