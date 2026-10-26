package com.clavaris.organization.infrastructure.adapter.in.web;

import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.ORGANIZATIONS_REDIRECT_PREFIX;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.ORGANIZATION_ATTRIBUTE;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.WORKSPACES_PATH_SEGMENT;
import static com.clavaris.organization.infrastructure.adapter.in.web.WorkspaceDashboardSupport.isHtmxRequest;

import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0028: the read model behind the Workspace-detail page's Teams &amp; Roles section and the
 * Teams tab's hierarchy — teams, the roles grouped into each, every Organization role with no team
 * in this Workspace ("ungrouped"), Shared by the Workspace, Team, Team-role, Hierarchy and
 * Assign-role controllers so none of them owns a second copy of the loading rules.
 */
@Component
// PMD.LongVariable/ExcessiveImports: a read model over several use cases and the domain types they
// return — wiring, not sprawl.
@SuppressWarnings({"PMD.ExcessiveImports"})
final class WorkspaceTeamsModel {

  /* default */ static final String WORKSPACE_DETAIL_VIEW =
      "organization/platform/workspace-detail";
  /* default */ static final String TEAMS_FRAGMENT = WORKSPACE_DETAIL_VIEW + " :: teams";
  /* default */ static final String CREATE_TEAM_FORM_ATTRIBUTE = "createTeamForm";
  /* default */ static final String CREATE_ROLE_FORM_ATTRIBUTE = "createRoleForm";

  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private final ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams;
  private final ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds;

  /* default */ WorkspaceTeamsModel(
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final ListWorkspaceTeamsForWorkspaceUseCase listTeams,
      final ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams,
      final ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds) {
    this.listRoles = listRoles;
    this.listTeams = listTeams;
    this.listTeamRoleIdsForTeams = listTeamRoleIdsForTeams;
    this.listGroupedRoleIds = listGroupedRoleIds;
  }

  // Shared "a team/role mutation just succeeded" tail — same rationale as
  // PlatformOrganizationDashboardController's own identical shape for its own resource.
  /* default */ String afterTeamMutation(
      final HttpServletRequest request,
      final Organization organization,
      final Workspace workspace,
      final Model model) {
    if (isHtmxRequest(request)) {
      model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
      populateTeamsModel(model, workspace);
      return TEAMS_FRAGMENT;
    }
    return ORGANIZATIONS_REDIRECT_PREFIX
        + organization.id()
        + WORKSPACES_PATH_SEGMENT
        + workspace.id();
  }

  // ADR-0028: teams (List<WorkspaceTeam>), the roles grouped into each one (Map<UUID, List
  // <WorkspaceRole>>, keyed by teamId), and every Organization role with no team association in
  // this Workspace ("ungrouped"). Reloads this Organization's own role catalog on every call — a
  // small, deliberate redundant read (this is a low-traffic admin dashboard).
  /* default */ void populateTeamsModel(final Model model, final Workspace workspace) {
    // Self-sufficient on "workspace" — several call sites are error/success paths that never
    // otherwise set it; a missing "workspace" here is a null workspace.name()
    // SpelEvaluationException on the very next render, not a graceful no-op.
    model.addAttribute("workspace", workspace);
    final TeamsAndRoles teamsAndRoles = loadTeamsAndRoles(workspace);

    model.addAttribute("teams", teamsAndRoles.teams());
    model.addAttribute("teamRoles", teamsAndRoles.rolesByTeamId());
    model.addAttribute("ungroupedRoles", teamsAndRoles.ungroupedRoles());
    model.addAttribute("allRolesWithTeam", flattenRolesWithTeam(teamsAndRoles));
    model.addAttribute(CREATE_TEAM_FORM_ATTRIBUTE, new CreateWorkspaceTeamForm());
    model.addAttribute(CREATE_ROLE_FORM_ATTRIBUTE, new CreateWorkspaceTeamRoleForm());
  }

  // Live UX request, 2026-10-02: the Roles section used to only ever show ungroupedRoles (team-
  // grouped roles were only visible nested inside their own team's own accordion row above) —
  // flattens teamsAndRoles' own two separate collections into one list, every role exactly once,
  // each carrying its own team's name (null for No team) so the Roles section's own table can
  // show every role with a Team column, searchable/filterable/paginated, instead of just the
  // ungrouped subset.
  private record RoleWithTeamName(WorkspaceRole role, UUID teamId, String teamName) {}

  private List<RoleWithTeamName> flattenRolesWithTeam(final TeamsAndRoles teamsAndRoles) {
    final Stream<RoleWithTeamName> grouped =
        teamsAndRoles.teams().stream()
            .flatMap(
                team ->
                    teamsAndRoles.rolesByTeamId().get(team.id()).stream()
                        .map(role -> new RoleWithTeamName(role, team.id(), team.name())));
    final Stream<RoleWithTeamName> ungrouped =
        teamsAndRoles.ungroupedRoles().stream().map(role -> new RoleWithTeamName(role, null, null));
    return Stream.concat(grouped, ungrouped)
        .sorted(Comparator.comparing(entry -> entry.role().name()))
        .toList();
  }

  /* default */ record TeamsAndRoles(
      List<WorkspaceTeam> teams,
      Map<UUID, List<WorkspaceRole>> rolesByTeamId,
      List<WorkspaceRole> ungroupedRoles) {}

  /* default */ TeamsAndRoles loadTeamsAndRoles(final Workspace workspace) {
    final Map<UUID, WorkspaceRole> rolesById =
        listRoles
            .handle(new ListWorkspaceRolesForOrganizationQuery(workspace.organizationId()))
            .stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    final List<WorkspaceTeam> teams =
        listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspace.id()));
    // TD-PERF-030: one batched call for every team's own roleIds, instead of one
    // listTeamRoleIds.handle call per team in this loop.
    final Map<UUID, List<UUID>> roleIdsByTeamId =
        listTeamRoleIdsForTeams.handle(
            new ListWorkspaceTeamRoleIdsForTeamsQuery(
                teams.stream().map(WorkspaceTeam::id).toList()));
    final Map<UUID, List<WorkspaceRole>> rolesByTeamId = new LinkedHashMap<>();
    for (final WorkspaceTeam team : teams) {
      final List<WorkspaceRole> teamRoles =
          roleIdsByTeamId.get(team.id()).stream()
              .map(rolesById::get)
              .filter(Objects::nonNull)
              .sorted(Comparator.comparing(WorkspaceRole::name))
              .toList();
      rolesByTeamId.put(team.id(), teamRoles);
    }

    final Set<UUID> groupedRoleIds =
        listGroupedRoleIds.handle(new ListGroupedWorkspaceRoleIdsQuery(workspace.id()));
    final List<WorkspaceRole> ungroupedRoles =
        rolesById.values().stream()
            .filter(role -> !groupedRoleIds.contains(role.id()))
            .sorted(Comparator.comparing(WorkspaceRole::name))
            .toList();

    return new TeamsAndRoles(teams, rolesByTeamId, ungroupedRoles);
  }

  // Resolves teamId's own roles from THIS workspace's real teams (loadTeamsAndRoles's own
  // rolesByTeamId map is keyed only by teamIds that genuinely belong to this workspace) — a teamId
  // that isn't one of them (wrong workspace, wrong organization, or just made up) 404s here before
  // either "Assign role" handler below does anything else with it.
  /* default */ List<WorkspaceRole> requireOwnedTeamRoles(
      final Workspace workspace, final UUID teamId) {
    final List<WorkspaceRole> teamRoles = loadTeamsAndRoles(workspace).rolesByTeamId().get(teamId);
    if (teamRoles == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return teamRoles;
  }

  // The error/re-render tail every Teams & Roles handler shares: put the Organization and a fresh
  // read model back on the page and answer with the fragment (HTMX) or the whole page (plain).
  /* default */ String rerender(
      final HttpServletRequest request,
      final Organization organization,
      final Workspace workspace,
      final Model model) {
    model.addAttribute(ORGANIZATION_ATTRIBUTE, organization);
    populateTeamsModel(model, workspace);
    return isHtmxRequest(request) ? TEAMS_FRAGMENT : WORKSPACE_DETAIL_VIEW;
  }
}
