package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.PlatformWorkspaceRoleSupport.ORGANIZATION_ATTRIBUTE;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
 * ADR-0027 Slice 5: the dashboard's own {@code WorkspaceRole} management — a Configure sub-page
 * (same shape as Rate Limit/Signing Keys/Webhook Endpoints) since a {@code WorkspaceRole} is
 * Organization-scoped, shared across every {@code Workspace} that Organization owns, not a single
 * Workspace's own concern. This class is the list and create half; {@link
 * PlatformWorkspaceRoleDetailController} holds view/edit/delete (TD-ARCH-042 split them), and
 * {@link PlatformWorkspaceRoleSupport} what they share. Every write goes through the same use cases
 * {@code /api/v1/admin/**} already exposes — this adds a second, session-authenticated {@link
 * AuditActor#platformAccount} caller, not a second implementation.
 *
 * <p>{@code organizationId} resolves through {@code GetOrganizationForPlatformAccountUseCase} —
 * never a bare repository call — so an organizationId this {@code PlatformAccount} doesn't own
 * resolves identically to "doesn't exist" (a 404), same anti-enumeration posture as {@link
 * PlatformWorkspaceController}.
 *
 * <p>{@code permissions} is a free-text, newline-separated field (opaque, consumer-defined strings
 * — same "generic infrastructure, not a fixed picklist" posture {@link WorkspaceRole}'s own Javadoc
 * documents), parsed by {@link PlatformWorkspaceRoleSupport#parsePermissions(String)} — unlike
 * {@code WebhookEndpoint.subscribedEventTypes}, there's no known, finite catalog to offer as
 * checkboxes.
 */
// PMD.ExcessiveImports/CouplingBetweenObjects: every import backs a real, distinct use case or
// exception; the "Team" column (buildRoleRows/teamLabelsByRoleId) composes use cases instead of
// adding a repository query — the tradeoff those two methods' own comments explain.
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/workspace-roles")
public class PlatformWorkspaceRoleController {

  private static final String LIST_VIEW = "organization/platform/workspace-roles";
  private static final String CREATE_VIEW = "organization/platform/create-workspace-role";
  private static final String CREATE_FORM_ATTRIBUTE = "createForm";

  private final ListWorkspacesForOrganizationUseCase listWorkspaces;
  private final ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private final ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams;
  private final CreateWorkspaceRoleUseCase createRole;
  private final PlatformWorkspaceRoleSupport support;

  public PlatformWorkspaceRoleController(
      final ListWorkspacesForOrganizationUseCase listWorkspaces,
      final ListWorkspaceTeamsForWorkspaceUseCase listTeams,
      final ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams,
      final CreateWorkspaceRoleUseCase createRole,
      final PlatformWorkspaceRoleSupport support) {
    this.listWorkspaces = listWorkspaces;
    this.listTeams = listTeams;
    this.listTeamRoleIdsForTeams = listTeamRoleIdsForTeams;
    this.createRole = createRole;
    this.support = support;
  }

  // Live UX request, 2026-10-02: restores this controller's own list page (Configure >
  // Workspace Roles) — removed 2026-10-02 in favor of the Teams & Roles "Roles" section's own
  // unified table, now restored alongside it rather than replaced by it. That table's own "Modify"/
  // "Advanced create" links still point into this same create/detail pair, now carrying their own
  // workspaceId so the Back/Cancel button below knows to return there instead of here.
  @GetMapping
  public String showList(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final Organization organization = support.requireOwnedOrganization(request, organizationId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    support.populateRolesModel(model, organizationId);
    model.addAttribute("roleRows", buildRoleRows(organizationId));
    return LIST_VIEW;
  }

  // Live UX request, 2026-10-02 (corrects an earlier same-day "Members count" version of this
  // column): the "Team" column — a WorkspaceRole is Organization-scoped (ADR-0027), reusable
  // across every Workspace that Organization owns, and ADR-0028 §2 only caps it at ONE team
  // PER Workspace — the same role can still be independently grouped into a DIFFERENT team in a
  // DIFFERENT Workspace of this same Organization. Rather than squeezing every grouping into one
  // cell, each (role, team-in-Workspace) pairing gets its OWN row — a role grouped in two
  // Workspaces appears twice, each row's own Team column naming both the team and its Workspace
  // (two same-named teams in different Workspaces are still two distinct WorkspaceTeam rows, so
  // the Workspace name is what actually disambiguates them). A role with no grouping anywhere
  // still gets exactly one row, with an empty Team cell.
  private record RoleRow(WorkspaceRole role, String teamLabel) {}

  private List<RoleRow> buildRoleRows(final UUID organizationId) {
    final Map<UUID, WorkspaceRole> rolesById = support.loadRolesById(organizationId);
    final Map<UUID, List<String>> teamLabelsByRoleId = teamLabelsByRoleId(organizationId);
    final List<RoleRow> rows = new ArrayList<>();
    for (final WorkspaceRole role :
        rolesById.values().stream().sorted(Comparator.comparing(WorkspaceRole::name)).toList()) {
      final List<String> labels = teamLabelsByRoleId.get(role.id());
      if (labels == null || labels.isEmpty()) {
        rows.add(new RoleRow(role, null));
      } else {
        labels.forEach(label -> rows.add(new RoleRow(role, label)));
      }
    }
    return rows;
  }

  // Composes ListWorkspacesForOrganizationUseCase + the same ListWorkspaceTeamsForWorkspaceUseCase/
  // ListWorkspaceTeamRoleIdsForTeamsUseCase pair PlatformWorkspaceController's own
  // loadTeamsAndRoles already uses for one Workspace, just run once per Workspace this
  // Organization owns — a low-traffic admin page, same "a small, deliberate redundant read"
  // tradeoff that method's own Javadoc already accepts, not a reason to add a new repository
  // query.
  private Map<UUID, List<String>> teamLabelsByRoleId(final UUID organizationId) {
    final List<Workspace> workspaces =
        listWorkspaces.handle(new ListWorkspacesForOrganizationQuery(organizationId));
    final Map<UUID, List<String>> labels = new HashMap<>();
    for (final Workspace workspace : workspaces) {
      final List<WorkspaceTeam> teams =
          listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspace.id()));
      final Map<UUID, List<UUID>> roleIdsByTeamId =
          listTeamRoleIdsForTeams.handle(
              new ListWorkspaceTeamRoleIdsForTeamsQuery(
                  teams.stream().map(WorkspaceTeam::id).toList()));
      for (final WorkspaceTeam team : teams) {
        for (final UUID roleId : roleIdsByTeamId.getOrDefault(team.id(), List.of())) {
          labels
              .computeIfAbsent(roleId, key -> new ArrayList<>())
              .add(team.name() + " - " + workspace.name());
        }
      }
    }
    return labels;
  }

  @GetMapping("/new")
  public String showCreateForm(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final UUID workspaceId,
      final Model model) {
    final Organization organization = support.requireOwnedOrganization(request, organizationId);
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    support.populateRolesModel(model, organizationId);
    support.populateBackTarget(model, organizationId, workspaceId);
    model.addAttribute(CREATE_FORM_ATTRIBUTE, new CreateWorkspaceRoleForm());
    return CREATE_VIEW;
  }

  @PostMapping
  public String create(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute(CREATE_FORM_ATTRIBUTE) final CreateWorkspaceRoleForm form,
      final BindingResult bindingResult,
      @RequestParam(required = false) final UUID workspaceId,
      final Model model) {
    final UUID ownerPlatformAccountId = support.requireCurrentPlatformAccount(request);
    final Organization organization =
        support.requireOwnedOrganization(organizationId, ownerPlatformAccountId);
    if (bindingResult.hasErrors()) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      support.populateRolesModel(model, organizationId);
      support.populateBackTarget(model, organizationId, workspaceId);
      return CREATE_VIEW;
    }

    try {
      createRole.handle(
          new CreateWorkspaceRoleCommand(
              organizationId,
              form.getName(),
              form.getParentRoleId(),
              PlatformWorkspaceRoleSupport.parsePermissions(form.getPermissionsText()),
              AuditActor.platformAccount(ownerPlatformAccountId)));
    } catch (final OrganizationNotFoundException _) {
      // Not expected on this path — support.requireOwnedOrganization above already confirmed
      // organizationId exists — but a loud 404 is still safer than assuming that can never race
      // with a concurrent deletion.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } catch (final DuplicateWorkspaceRoleNameException _) {
      model.addAttribute("duplicateNameError", true);
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      support.populateRolesModel(model, organizationId);
      support.populateBackTarget(model, organizationId, workspaceId);
      return CREATE_VIEW;
    } catch (final WorkspaceRoleNotFoundException _) {
      // The parent-role dropdown only ever offers this Organization's own real roles — reaching
      // this means the submitted parentRoleId was tampered with, not a real user mistake.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    return support.redirectToList(organizationId);
  }
}
