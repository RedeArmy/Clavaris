package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.FORCE_CONFIRMATION_WORD;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceTeamsModel.CREATE_TEAM_FORM_ATTRIBUTE;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.WorkspaceRoleAlreadyInAnotherTeamException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamCommand;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
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
 * ADR-0028: the Workspace-detail page's Teams section — create, rename and delete a Team, and add
 * already-existing ungrouped roles to one. Every write goes through the same use cases a future
 * REST admin API would (none exists yet for Teams — dashboard-only, with no permission semantics of
 * its own per the ADR's §4). See {@link PlatformWorkspaceController} for the family this belongs to
 * and the shared ownership/HTMX conventions.
 */
// PMD.LongVariable/ExcessiveImports: as above. PMD.AvoidDuplicateLiterals: "PMD.OnlyOneReturn" is
// repeated once per handler that legitimately needs it, same false-positive class
// ContentSecurityPolicyHeaderWriter's own identical class-level suppression documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports", "PMD.AvoidDuplicateLiterals"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspaces")
public class PlatformWorkspaceTeamController {

  private final WorkspaceDashboardSupport access;
  private final WorkspaceTeamsModel teamsModel;
  private final CreateWorkspaceTeamUseCase createTeamUseCase;
  private final RenameWorkspaceTeamUseCase renameTeamUseCase;
  private final DeleteWorkspaceTeamUseCase deleteTeamUseCase;
  private final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase;

  public PlatformWorkspaceTeamController(
      final WorkspaceDashboardSupport access,
      final WorkspaceTeamsModel teamsModel,
      final CreateWorkspaceTeamUseCase createTeamUseCase,
      final RenameWorkspaceTeamUseCase renameTeamUseCase,
      final DeleteWorkspaceTeamUseCase deleteTeamUseCase,
      final AddRoleToWorkspaceTeamUseCase addRoleToTeamUseCase) {
    this.access = access;
    this.teamsModel = teamsModel;
    this.createTeamUseCase = createTeamUseCase;
    this.renameTeamUseCase = renameTeamUseCase;
    this.deleteTeamUseCase = deleteTeamUseCase;
    this.addRoleToTeamUseCase = addRoleToTeamUseCase;
  }

  // ADR-0028: the Workspace-detail page's own Teams & Roles section — create/rename/delete a team,
  // create a WorkspaceRole and assign it to a team in one popup, add one of this Organization's
  // already-existing ungrouped roles to a team, and modify/delete a role directly. Every write here
  // goes through the exact same use cases a future REST admin API would (none exists yet for Teams
  // — dashboard-only in this increment, same "no permission semantics of its own" posture the ADR's
  // own §4/open-question-3 documents).
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/teams")
  public String createTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @Valid @ModelAttribute(CREATE_TEAM_FORM_ATTRIBUTE) final CreateWorkspaceTeamForm form,
      final BindingResult bindingResult,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    if (bindingResult.hasErrors()) {
      return teamsModel.rerender(request, organization, workspace, model);
    }

    try {
      createTeamUseCase.handle(
          new CreateWorkspaceTeamCommand(
              workspaceId, form.getName(), AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final DuplicateWorkspaceTeamNameException _) {
      model.addAttribute("duplicateTeamNameError", true);
      return teamsModel.rerender(request, organization, workspace, model);
    }

    return teamsModel.afterTeamMutation(request, organization, workspace, model);
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
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);

    try {
      renameTeamUseCase.handle(
          new RenameWorkspaceTeamCommand(
              workspaceId, teamId, name, AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final DuplicateWorkspaceTeamNameException _) {
      model.addAttribute("duplicateTeamNameError", true);
      return teamsModel.rerender(request, organization, workspace, model);
    }

    return teamsModel.afterTeamMutation(request, organization, workspace, model);
  }

  // Live UX request, 2026-09-29: unlike deleteRole/unassignMemberFromRole above, a
  // CannotDemoteLastAdminException here means the WHOLE team deletion aborted (nothing was
  // deleted, DeleteWorkspaceTeamService's own Javadoc explains why) — the confirm-and-retry
  // popup asks to redo the entire action with force=true, not just one role's own unassign.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/teams/{teamId}/delete")
  public String deleteTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      @RequestParam(required = false) final String confirmedAction,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);
    final boolean force = FORCE_CONFIRMATION_WORD.equals(confirmedAction);

    try {
      deleteTeamUseCase.handle(
          new DeleteWorkspaceTeamCommand(
              organizationId,
              workspaceId,
              teamId,
              force,
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final WorkspaceTeamNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final CannotDemoteLastAdminException _) {
      model.addAttribute(CANNOT_DEMOTE_LAST_ADMIN_ERROR_ATTRIBUTE, true);
      model.addAttribute("forceConfirmTeamId", teamId);
      return teamsModel.rerender(request, organization, workspace, model);
    }

    return teamsModel.afterTeamMutation(request, organization, workspace, model);
  }

  // Attaches one of this Organization's already-existing, currently-ungrouped roles to a team —
  // kept alongside the "create a new role" popup below for the case where a role was created (or
  // left ungrouped after its own team was deleted) via Configure > Workspace Roles without one.
  // Live UX request, 2026-10-01: the single-select <select> + "Add existing role" pair became a
  // paginated, multi-select checkbox popup — one submit can now carry several roleIds at once.
  // Each is still added through the same one-role-at-a-time AddRoleToWorkspaceTeamUseCase (its own
  // Javadoc: idempotent per role, its own @Transactional boundary) — a failure partway through this
  // loop leaves whatever was added before it committed, same end state as if the operator had
  // submitted them one at a time.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/{workspaceId}/teams/{teamId}/roles")
  public String addRoleToTeam(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID workspaceId,
      @PathVariable final UUID teamId,
      @RequestParam(required = false) final List<UUID> roleIds,
      final Model model) {
    final UUID ownerPlatformAccountId = access.requireCurrentPlatformAccount(request);
    final Organization organization =
        access.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    final Workspace workspace = access.requireOwnedWorkspace(organizationId, workspaceId);

    try {
      for (final UUID roleId : roleIds == null ? List.<UUID>of() : roleIds) {
        addRoleToTeamUseCase.handle(
            new AddRoleToWorkspaceTeamCommand(
                workspaceId, teamId, roleId, AuditActor.platformAccount(ownerPlatformAccountId)));
      }
    } catch (final WorkspaceTeamNotFoundException | WorkspaceRoleNotFoundException _) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final WorkspaceRoleAlreadyInAnotherTeamException _) {
      model.addAttribute("roleAlreadyInAnotherTeamError", true);
      return teamsModel.rerender(request, organization, workspace, model);
    }

    return teamsModel.afterTeamMutation(request, organization, workspace, model);
  }
}
