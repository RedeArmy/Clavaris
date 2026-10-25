package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.FORCE_CONFIRMATION_WORD;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceTeamsModel.CREATE_ROLE_FORM_ATTRIBUTE;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0028: creating a brand-new {@code WorkspaceRole} (optionally into a Team in the same popup)
 * and deleting one, from the Workspace-detail page. Full role editing stays on the Configure &gt;
 * Workspace Roles page ({@link PlatformWorkspaceRoleController}); "Modify" links there rather than
 * duplicating its form. See {@link PlatformWorkspaceController} for the family this belongs to.
 */
// PMD.LongVariable: collaborator names spell out the use case they hold. PMD.ExcessiveImports:
// every import is a distinct use case, command or exception this controller genuinely handles —
// wiring, not sprawl (the whole Workspace surface used to be one class).
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspaces")
public class PlatformWorkspaceTeamRoleController {

  private final WorkspaceDashboardSupport access;
  private final WorkspaceTeamsModel teamsModel;
  private final CreateWorkspaceRoleUseCase createRoleUseCase;
  private final DeleteWorkspaceRoleUseCase deleteRoleUseCase;
  private final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase;

  public PlatformWorkspaceTeamRoleController(
      final WorkspaceDashboardSupport access,
      final WorkspaceTeamsModel teamsModel,
      final CreateWorkspaceRoleUseCase createRoleUseCase,
      final DeleteWorkspaceRoleUseCase deleteRoleUseCase,
      final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase) {
    this.access = access;
    this.teamsModel = teamsModel;
    this.createRoleUseCase = createRoleUseCase;
    this.deleteRoleUseCase = deleteRoleUseCase;
    this.addRoleToTeamUseCase = addRoleToTeamUseCase;
  }

  // SDE-III redesign, 2026-09-27: creates a brand-new WorkspaceRole and, when a team was picked,
  // assigns it in the same popup submit — composes CreateWorkspaceRoleUseCase with
  // AddRoleToWorkspaceTeamUseCase, same "two use cases, one form" shape addMember's own removed
  // Account+WorkspaceMembership composition used. parentRoleId/permissions are deliberately absent
  // from this popup's form (Set.of()/null) — those stay editable only from the full Configure >
  // Workspace Roles page ("Modify" links there, never duplicated into a second form here).
  // WorkspaceRoleAlreadyInAnotherTeamException isn't caught here: the role this popup just created
  // has a freshly-minted, never-before-seen id, so it cannot already belong to another team — not a
  // race this path can hit, unlike addRoleToTeam's own identical catch for a pre-existing role id.
  //
  // Live UX request, 2026-09-28: teamId is now optional — "No team" (CreateWorkspaceTeamRole
  // Form's own Javadoc) is a fully supported first-class choice, not just something a role ends up
  // in after its team is later deleted. form.getTeamId() == null skips
  // AddRoleToWorkspaceTeamUseCase
  // entirely; the role this popup just created is simply left ungrouped.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/roles")
  public String createRole(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @Valid @ModelAttribute(CREATE_ROLE_FORM_ATTRIBUTE) final CreateWorkspaceTeamRoleForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    if (bindingResult.hasErrors()) {
      return teamsModel.rerender(request, organization, workspace, model);
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
      return teamsModel.rerender(request, organization, workspace, model);
    }

    if (form.getTeamId() != null) {
      try {
        addRoleToTeamUseCase.handle(
            new AddRoleToWorkspaceTeamCommand(
                workspaceId,
                form.getTeamId(),
                role.id(),
                AuditActor.platformAccount(ownerPlatformAccountId)));
      } catch (final WorkspaceTeamNotFoundException _) {
        // The popup's own team <select> only ever offers this Workspace's own real teams or the
        // synthetic "No team" (null) option — reaching this means the submitted teamId was
        // tampered with, not a real user mistake.
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
    }

    return teamsModel.afterTeamMutation(request, organization, workspace, model);
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
      @RequestParam(required = false) final String confirmedAction,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    access.requireOwnedRole(organizationId, roleId);
    final boolean force = FORCE_CONFIRMATION_WORD.equals(confirmedAction);

    try {
      deleteRoleUseCase.handle(
          new DeleteWorkspaceRoleCommand(
              roleId,
              organizationId,
              workspaceId,
              force,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDeleteReservedWorkspaceRoleException _) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } catch (final WorkspaceRoleStillAssignedException _) {
      model.addAttribute("roleStillAssignedError", true);
      return teamsModel.rerender(request, organization, workspace, model);
    } catch (final WorkspaceRoleHasChildRolesException _) {
      model.addAttribute("roleHasChildRolesError", true);
      return teamsModel.rerender(request, organization, workspace, model);
    } catch (final CannotDemoteLastAdminException _) {
      // Live UX request, 2026-09-28: deleting this role now auto-unassigns every holder in this
      // Workspace first (DeleteWorkspaceRoleService's own Javadoc) — this is that bulk unassign
      // hitting the exact same ManageMembersGuard every other role-changing action already does.
      // 2026-09-29: the dashboard may now bypass it on explicit "type UNASSIGN" confirmation —
      // see ChangeWorkspaceMemberRoleCommand#force()'s own Javadoc.
      model.addAttribute(CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE, true);
      model.addAttribute("forceConfirmRoleId", roleId);
      return teamsModel.rerender(request, organization, workspace, model);
    }

    return teamsModel.afterTeamMutation(request, organization, workspace, model);
  }
}
