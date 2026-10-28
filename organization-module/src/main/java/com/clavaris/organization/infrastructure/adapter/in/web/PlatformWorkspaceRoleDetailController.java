package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.PlatformWorkspaceRoleSupport.ORGANIZATIONS_REDIRECT_PREFIX;
import static com.clavaris.organization.infrastructure.adapter.in.web.PlatformWorkspaceRoleSupport.ORGANIZATION_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.PlatformWorkspaceRoleSupport.ROLE_ATTRIBUTE;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.updateworkspacerole.CannotStripReservedWorkspaceRolePermissionsException;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.WorkspaceRoleCycleException;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
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
 * ADR-0027 Slice 5, the detail half (TD-ARCH-042 split it from {@link
 * PlatformWorkspaceRoleController}, which keeps the list and create pages): view, edit and delete
 * one {@code WorkspaceRole}. Every write goes through the same use cases {@code /api/v1/admin/**}
 * exposes — this adds a session-authenticated {@link AuditActor#platformAccount} caller, not a
 * second implementation.
 *
 * <p>{@link UpdateWorkspaceRoleCommand}/{@link DeleteWorkspaceRoleCommand} key off {@code roleId}
 * alone, so this controller always resolves the target from this Organization's own role list
 * first: a {@code roleId} belonging to a different Organization 404s here before either use case is
 * called.
 *
 * <p>{@link WorkspaceRoleStillAssignedException} is a real, owner-reachable outcome (deleting a
 * role members still hold) — re-rendered as a detail-page error, not redirected, same treatment
 * {@link PlatformWorkspaceController#changeRole}'s own {@code CannotDemoteLastAdminException} catch
 * establishes. {@link CannotDeleteReservedWorkspaceRoleException} is not reachable through this UI
 * (the page never renders a delete control for a reserved role) — a loud 409 is safer than assuming
 * that can never race with a tampered form.
 */
// PMD.ExcessiveImports: one import per distinct use case exception this page maps to a response.
@SuppressWarnings("PMD.ExcessiveImports")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspace-roles")
public class PlatformWorkspaceRoleDetailController {

  private static final String DETAIL_VIEW = "organization/platform/workspace-role-detail";
  private static final String EDIT_FORM_ATTRIBUTE = "editForm";

  private final UpdateWorkspaceRoleUseCase updateRole;
  private final DeleteWorkspaceRoleUseCase deleteRole;
  private final PlatformWorkspaceRoleSupport support;

  public PlatformWorkspaceRoleDetailController(
      final UpdateWorkspaceRoleUseCase updateRole,
      final DeleteWorkspaceRoleUseCase deleteRole,
      final PlatformWorkspaceRoleSupport support) {
    this.updateRole = updateRole;
    this.deleteRole = deleteRole;
    this.support = support;
  }

  @GetMapping("/{roleId}")
  public String showDetail(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      @RequestParam(required = false) final UUID workspaceId,
      final Model model) {
    final Organization organization = support.requireOwnedOrganization(request, organizationId);
    final Map<UUID, WorkspaceRole> rolesById = support.loadRolesById(organizationId);
    final WorkspaceRole role = requireOwnedRole(rolesById, roleId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    support.populateRolesModel(model, rolesById);
    support.populateBackTarget(model, organizationId, workspaceId);
    model.addAttribute(ROLE_ATTRIBUTE, role);
    model.addAttribute(EDIT_FORM_ATTRIBUTE, formFor(role));
    return DETAIL_VIEW;
  }

  // java:S107: one parameter per real, distinct input this handler genuinely needs (request
  // context, path/form/binding, and workspaceId for the Back button's own return target).
  @SuppressWarnings({"java:S107"})
  @PostMapping("/{roleId}")
  public String update(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      @Valid @ModelAttribute(EDIT_FORM_ATTRIBUTE) final UpdateWorkspaceRoleForm form,
      final BindingResult bindingResult,
      @RequestParam(required = false) final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = support.requireCurrentPlatformAccount(request);
    final Organization organization =
        support.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Map<UUID, WorkspaceRole> rolesById = support.loadRolesById(organizationId);
    final WorkspaceRole existing = requireOwnedRole(rolesById, roleId);

    if (bindingResult.hasErrors()) {
      return redisplayDetail(model, organization, rolesById, existing, null, workspaceId);
    }

    final WorkspaceRole updated;
    try {
      updated =
          updateRole.handle(
              new UpdateWorkspaceRoleCommand(
                  roleId,
                  organizationId,
                  form.getName(),
                  form.getParentRoleId(),
                  PlatformWorkspaceRoleSupport.parsePermissions(form.getPermissionsText()),
                  AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final DuplicateWorkspaceRoleNameException _) {
      return redisplayDetail(
          model, organization, rolesById, existing, "duplicateNameError", workspaceId);
    } catch (final WorkspaceRoleCycleException _) {
      return redisplayDetail(
          model, organization, rolesById, existing, "parentCycleError", workspaceId);
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

    return ORGANIZATIONS_REDIRECT_PREFIX + organizationId + "/workspace-roles/" + updated.id();
  }

  @PostMapping("/{roleId}/delete")
  public String delete(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID roleId,
      @RequestParam(required = false) final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = support.requireCurrentPlatformAccount(request);
    final Organization organization =
        support.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Map<UUID, WorkspaceRole> rolesById = support.loadRolesById(organizationId);
    final WorkspaceRole existing = requireOwnedRole(rolesById, roleId);

    try {
      deleteRole.handle(
          new DeleteWorkspaceRoleCommand(
              roleId, organizationId, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDeleteReservedWorkspaceRoleException _) {
      // Not reachable through this UI — see this class's own Javadoc.
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } catch (final WorkspaceRoleStillAssignedException _) {
      return redisplayDetail(
          model, organization, rolesById, existing, "stillAssignedError", workspaceId);
    } catch (final WorkspaceRoleHasChildRolesException _) {
      return redisplayDetail(
          model, organization, rolesById, existing, "hasChildRolesError", workspaceId);
    }

    return support.redirectToList(organizationId);
  }

  // java:S107: one parameter per real, distinct input.
  @SuppressWarnings("java:S107")
  private String redisplayDetail(
      final Model model,
      final Organization organization,
      final Map<UUID, WorkspaceRole> rolesById,
      final WorkspaceRole existing,
      final String errorAttribute,
      final UUID workspaceId) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    support.populateRolesModel(model, rolesById);
    support.populateBackTarget(model, organization.id(), workspaceId);
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
    form.setPermissionsText(PlatformWorkspaceRoleSupport.joinPermissions(role.permissions()));
    return form;
  }

  private static WorkspaceRole requireOwnedRole(
      final Map<UUID, WorkspaceRole> rolesById, final UUID roleId) {
    final WorkspaceRole role = rolesById.get(roleId);
    if (role == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return role;
  }
}
