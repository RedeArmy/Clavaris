package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.WorkspaceRoleAlreadyInAnotherTeamException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link
 * PlatformOrganizationDashboardControllerTest}/{@link PlatformOrganizationDetailControllerTest}.
 */
class PlatformWorkspaceControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetWorkspaceForOrganizationUseCase getWorkspace;
  private ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private ListWorkspaceRolesForOrganizationUseCase listRoles;
  private CreateWorkspaceUseCase createWorkspace;
  private DeleteWorkspaceUseCase deleteWorkspace;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds;
  private CreateWorkspaceTeamUseCase createTeam;
  private RenameWorkspaceTeamUseCase renameTeam;
  private DeleteWorkspaceTeamUseCase deleteTeam;
  private AddRoleToWorkspaceTeamUseCase addRoleToTeam;
  private CreateWorkspaceRoleUseCase createRole;
  private DeleteWorkspaceRoleUseCase deleteRole;
  private ListWorkspaceMembersUseCase listMembers;
  private OrganizationAccountDirectory accountDirectory;
  private AssignWorkspaceRoleToAccountUseCase assignRoleToAccount;
  private MockMvc mockMvc;
  private Organization organization;
  private Workspace workspace;
  private WorkspaceRole role;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getWorkspace = mock(GetWorkspaceForOrganizationUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationPagedUseCase.class);
    listRoles = mock(ListWorkspaceRolesForOrganizationUseCase.class);
    createWorkspace = mock(CreateWorkspaceUseCase.class);
    deleteWorkspace = mock(DeleteWorkspaceUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    listTeams = mock(ListWorkspaceTeamsForWorkspaceUseCase.class);
    listTeamRoleIds = mock(ListWorkspaceTeamRoleIdsUseCase.class);
    listGroupedRoleIds = mock(ListGroupedWorkspaceRoleIdsUseCase.class);
    createTeam = mock(CreateWorkspaceTeamUseCase.class);
    renameTeam = mock(RenameWorkspaceTeamUseCase.class);
    deleteTeam = mock(DeleteWorkspaceTeamUseCase.class);
    addRoleToTeam = mock(AddRoleToWorkspaceTeamUseCase.class);
    createRole = mock(CreateWorkspaceRoleUseCase.class);
    deleteRole = mock(DeleteWorkspaceRoleUseCase.class);
    listMembers = mock(ListWorkspaceMembersUseCase.class);
    accountDirectory = mock(OrganizationAccountDirectory.class);
    assignRoleToAccount = mock(AssignWorkspaceRoleToAccountUseCase.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    workspace = Workspace.register(organization.id(), "Engineering");
    role = WorkspaceRole.defineReserved(organization.id(), "Admin");

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getWorkspace.handle(any())).thenReturn(Optional.of(workspace));
    when(listWorkspaces.handle(any())).thenReturn(emptyWorkspacesPage());
    when(listRoles.handle(any())).thenReturn(List.of(role));
    when(listTeams.handle(any())).thenReturn(List.of());
    when(listTeamRoleIds.handle(any())).thenReturn(List.of());
    when(listGroupedRoleIds.handle(any())).thenReturn(Set.of());
    when(listMembers.handle(any())).thenReturn(List.of());
    when(accountDirectory.listAccountsForOrganization(any())).thenReturn(List.of());

    GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();

    SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");

    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);

    ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformWorkspaceController(
                    getOrganization,
                    getWorkspace,
                    listWorkspaces,
                    listRoles,
                    createWorkspace,
                    deleteWorkspace,
                    currentPlatformAccount,
                    listTeams,
                    listTeamRoleIds,
                    listGroupedRoleIds,
                    createTeam,
                    renameTeam,
                    deleteTeam,
                    addRoleToTeam,
                    createRole,
                    deleteRole,
                    listMembers,
                    accountDirectory,
                    assignRoleToAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String workspacesPath() {
    return "/platform/dashboard/organizations/" + organization.id() + "/workspaces";
  }

  private String teamsPath() {
    return workspacesPath() + "/" + workspace.id() + "/teams";
  }

  private String rolesPath() {
    return workspacesPath() + "/" + workspace.id() + "/roles";
  }

  private static KeysetPage<Workspace> emptyWorkspacesPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  @Test
  void plainCreatePostRedirectsAfterCreatingAWorkspace() throws Exception {
    when(createWorkspace.handle(any())).thenReturn(workspace);

    mockMvc
        .perform(post(workspacesPath()).param("name", "Engineering"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + organization.id()));

    verify(createWorkspace).handle(any());
  }

  @Test
  void createPostWithNoNameReRendersTheOrganizationPageWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post(workspacesPath()).param("name", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"));

    verify(createWorkspace, never()).handle(any());
  }

  @Test
  void htmxCreatePostReturnsTheWorkspacesFragment() throws Exception {
    when(createWorkspace.handle(any())).thenReturn(workspace);

    mockMvc
        .perform(post(workspacesPath()).param("name", "Engineering").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"));
  }

  @Test
  void showsTheWorkspaceAndItsTeams() throws Exception {
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));

    mockMvc
        .perform(get(workspacesPath() + "/" + workspace.id()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("workspace", workspace))
        .andExpect(model().attribute("teams", List.of(team)));
  }

  @Test
  void showsABackLinkToTheOrganizationsWorkspacesTab() throws Exception {
    String organizationDetailPath = "/platform/dashboard/organizations/" + organization.id();

    mockMvc
        .perform(get(workspacesPath() + "/" + workspace.id()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("clavaris-back-link")))
        .andExpect(content().string(containsString("href=\"" + organizationDetailPath + "\"")));
  }

  @Test
  void returnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization() throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get(workspacesPath() + "/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(workspacesPath() + "/" + workspace.id())).andExpect(status().isNotFound());
  }

  // Live UX request, 2026-09-28 (revised same day): "Delete" next to "View" on the Workspaces
  // list, gated behind a popup requiring the Workspace's own current name to be typed in.
  @Test
  void plainDeleteWorkspacePostRedirectsOnSuccessWhenTheTypedNameMatches() throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", workspace.name()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + organization.id()));

    verify(deleteWorkspace).handle(any());
  }

  @Test
  void htmxDeleteWorkspacePostReturnsTheWorkspacesFragmentWhenTheTypedNameMatches()
      throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", workspace.name())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"));

    verify(deleteWorkspace).handle(any());
  }

  @Test
  void plainDeleteWorkspacePostWithTheWrongTypedNameReRendersWithAMismatchErrorInsteadOfDeleting()
      throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", "not the real name"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"))
        .andExpect(model().attribute("deleteWorkspaceMismatchId", workspace.id()));

    verify(deleteWorkspace, never()).handle(any());
  }

  @Test
  void htmxDeleteWorkspacePostWithTheWrongTypedNameReturnsTheFragmentWithAMismatchError()
      throws Exception {
    mockMvc
        .perform(
            post(workspacesPath() + "/" + workspace.id() + "/delete")
                .param("confirmedName", "not the real name")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"))
        .andExpect(model().attribute("deleteWorkspaceMismatchId", workspace.id()));

    verify(deleteWorkspace, never()).handle(any());
  }

  @Test
  void deleteWorkspaceReturnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization()
      throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post(workspacesPath() + "/" + UUID.randomUUID() + "/delete")
                .param("confirmedName", "anything"))
        .andExpect(status().isNotFound());

    verify(deleteWorkspace, never()).handle(any());
  }

  // SDE-III addition, 2026-09-27: the Teams tab's own read-only hierarchy.
  @Test
  void showsTheTeamsHierarchyPage() throws Exception {
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of(role.id()));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"))
        .andExpect(model().attribute("teams", List.of(team)));
  }

  // Live UX request, 2026-09-28: a bare accountId used to render here — real gap, closed via
  // OrganizationAccountDirectory (same cross-module port the "Assign role" picker already uses).
  @Test
  void showsEveryMemberHoldingARoleInTheHierarchyByNameNotId() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembers.handle(any()))
        .thenReturn(List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())));
    when(accountDirectory.listAccountsForOrganization(any()))
        .thenReturn(List.of(new OrganizationAccountSummary(accountId, "Jane Doe")));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Jane Doe")))
        .andExpect(
            content().string(org.hamcrest.Matchers.not(containsString(accountId.toString()))));
  }

  // Defensive only — every real accountId here comes from a WorkspaceMembership, which is only
  // ever created against an account the directory already returned
  // (AccountNotInOrganizationException
  // guards that at creation time). Covers the fallback itself, not a reachable production gap.
  @Test
  void fallsBackToTheRawAccountIdWhenItIsMissingFromTheDirectory() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembers.handle(any()))
        .thenReturn(List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(accountId.toString())));
  }

  // ADR-0027 §5: a membership can be roleless — that account must not blow up the hierarchy's own
  // per-role grouping (its accountId simply never appears under any role).
  @Test
  void doesNotBlowUpOnARolelessMembership() throws Exception {
    when(listMembers.handle(any()))
        .thenReturn(List.of(WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), null)));

    mockMvc.perform(get(teamsPath())).andExpect(status().isOk());
  }

  @Test
  void groupsAnUngroupedRoleUnderNoTeamInTheHierarchy() throws Exception {
    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("ungroupedRoles", List.of(role)))
        .andExpect(content().string(containsString("No team")));
  }

  @Test
  void teamsHierarchyReturnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization()
      throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(teamsPath())).andExpect(status().isNotFound());
  }

  @Test
  void htmxGetOnTheTeamsHierarchyReturnsJustItsOwnFragment() throws Exception {
    mockMvc
        .perform(get(teamsPath()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy :: hierarchy"));
  }

  // SDE-III addition, 2026-09-27: "Assign role" popup scoped to one specific team (Way 1 of the
  // live UX request — the Teams tab's own per-team/per-"No team" trigger).
  @Test
  void showsEveryOrganizationAccountNotAlreadyInThisTeamAsEligible() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of(role.id()));
    OrganizationAccountSummary eligible =
        new OrganizationAccountSummary(UUID.randomUUID(), "eligible@example.com");
    OrganizationAccountSummary alreadyInTeam =
        new OrganizationAccountSummary(UUID.randomUUID(), "already@example.com");
    when(accountDirectory.listAccountsForOrganization(organization.id()))
        .thenReturn(List.of(eligible, alreadyInTeam));
    when(listMembers.handle(any()))
        .thenReturn(
            List.of(
                WorkspaceMembership.join(workspace.id(), alreadyInTeam.accountId(), role.id())));

    mockMvc
        .perform(get(teamsPath() + "/" + teamId + "/assign-role"))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("eligibleAccounts", List.of(eligible)))
        .andExpect(model().attribute("groupRoles", List.of(role)));
  }

  @Test
  void assignRoleFormForAnUnknownTeamReturnsNotFound() throws Exception {
    mockMvc
        .perform(get(teamsPath() + "/" + UUID.randomUUID() + "/assign-role"))
        .andExpect(status().isNotFound());
  }

  @Test
  void plainAssignRoleForTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of(role.id()));
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", accountId.toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id() + "/teams"));

    verify(assignRoleToAccount).handle(any());
  }

  @Test
  void htmxAssignRoleForTeamPostFiresTheRoleAssignedTriggerOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of(role.id()));

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Trigger", "workspace-role-assigned"))
        .andExpect(
            view()
                .name("organization/platform/fragments/team-assign-role-form :: assignRoleSaved"));
  }

  // Defense in depth: the popup's own Role <select> only ever offers this team's own roles.
  @Test
  void assignRoleForTeamWithARoleNotBelongingToThatTeamReturnsBadRequest() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of());
    WorkspaceRole otherRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", otherRole.id().toString()))
        .andExpect(status().isBadRequest());

    verify(assignRoleToAccount, never()).handle(any());
  }

  @Test
  void assignRoleForTeamRendersAnErrorInsteadOfPropagatingTheException() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of(role.id()));
    doThrow(new CannotDemoteLastAdminException(workspace.id()))
        .when(assignRoleToAccount)
        .handle(any());

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true));
  }

  // SDE-III addition, 2026-09-27: the synthetic "No team" group's own "Assign role" popup.
  @Test
  void showsTheAssignRoleFormForTheNoTeamGroup() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/assign-role"))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("groupRoles", List.of(role)));
  }

  @Test
  void plainAssignRoleForNoTeamPostRedirectsOnSuccess() throws Exception {
    mockMvc
        .perform(
            post(rolesPath() + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id() + "/teams"));

    verify(assignRoleToAccount).handle(any());
  }

  @Test
  void assignRoleForNoTeamWithARoleThatIsGroupedIntoATeamReturnsBadRequest() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listGroupedRoleIds.handle(any())).thenReturn(Set.of(role.id()));

    mockMvc
        .perform(
            post(rolesPath() + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isBadRequest());

    verify(assignRoleToAccount, never()).handle(any());
  }

  // Anti-cross-tenant: AssignWorkspaceRoleToAccountService's own accountId re-verification.
  @Test
  void assignRoleRendersNotFoundWhenTheAccountDoesNotBelongToThisOrganization() throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new AccountNotInOrganizationException(accountId, organization.id()))
        .when(assignRoleToAccount)
        .handle(any());

    mockMvc
        .perform(
            post(rolesPath() + "/assign-role")
                .param("accountId", accountId.toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isNotFound());
  }

  // ADR-0028: the Workspace-detail page's own Teams & Roles section.
  @Test
  void plainCreateTeamPostRedirectsOnSuccess() throws Exception {
    mockMvc
        .perform(post(teamsPath()).param("name", "QA"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(createTeam).handle(any());
  }

  @Test
  void htmxCreateTeamPostReturnsTheTeamsFragment() throws Exception {
    mockMvc
        .perform(post(teamsPath()).param("name", "QA").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail :: teams"));
  }

  @Test
  void createTeamWithADuplicateNameRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    doThrow(new DuplicateWorkspaceTeamNameException("QA")).when(createTeam).handle(any());

    mockMvc
        .perform(post(teamsPath()).param("name", "QA"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("duplicateTeamNameError", true));
  }

  @Test
  void plainRenameTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/rename").param("name", "Quality Assurance"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(renameTeam).handle(any());
  }

  @Test
  void plainDeleteTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(deleteTeam).handle(any());
  }

  @Test
  void plainAddRoleToTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles").param("roleId", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam).handle(any());
  }

  @Test
  void addRoleToTeamAlreadyInAnotherTeamRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new WorkspaceRoleAlreadyInAnotherTeamException(role.id(), UUID.randomUUID()))
        .when(addRoleToTeam)
        .handle(any());

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles").param("roleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("roleAlreadyInAnotherTeamError", true));
  }

  // SDE-III redesign, 2026-09-27: "Create a new role" popup — composes CreateWorkspaceRoleUseCase
  // with AddRoleToWorkspaceTeamUseCase in one submit.
  @Test
  void plainCreateRolePostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceRole created = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(createRole.handle(any())).thenReturn(created);

    mockMvc
        .perform(post(rolesPath()).param("name", "Reviewer").param("teamId", teamId.toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(createRole).handle(any());
    verify(addRoleToTeam).handle(any());
  }

  @Test
  void htmxCreateRolePostReturnsTheTeamsFragment() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceRole created = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(createRole.handle(any())).thenReturn(created);

    mockMvc
        .perform(
            post(rolesPath())
                .param("name", "Reviewer")
                .param("teamId", teamId.toString())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail :: teams"));
  }

  @Test
  void createRoleWithNoNameReRendersThePageWithoutCreatingAnything() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(rolesPath()).param("name", "").param("teamId", teamId.toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"));

    verify(createRole, never()).handle(any());
    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void createRoleWithADuplicateNameRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new DuplicateWorkspaceRoleNameException("Reviewer")).when(createRole).handle(any());

    mockMvc
        .perform(post(rolesPath()).param("name", "Reviewer").param("teamId", teamId.toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("duplicateRoleNameError", true));

    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void plainDeleteRolePostRedirectsOnSuccess() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(deleteRole).handle(any());
  }

  @Test
  void deleteRoleNotBelongingToThisOrganizationReturnsNotFound() throws Exception {
    mockMvc
        .perform(post(rolesPath() + "/" + UUID.randomUUID() + "/delete"))
        .andExpect(status().isNotFound());

    verify(deleteRole, never()).handle(any());
  }

  @Test
  void deleteRoleStillAssignedRendersAnErrorInsteadOfPropagatingTheException() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));
    doThrow(new WorkspaceRoleStillAssignedException(plainRole.id())).when(deleteRole).handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("roleStillAssignedError", true));
  }

  @Test
  void deleteRoleWithChildRolesRendersAnErrorInsteadOfPropagatingTheException() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));
    doThrow(new WorkspaceRoleHasChildRolesException(plainRole.id())).when(deleteRole).handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("roleHasChildRolesError", true));
  }

  @Test
  void deleteReservedRoleReturnsConflictInsteadOfPropagatingTheException() throws Exception {
    doThrow(new CannotDeleteReservedWorkspaceRoleException(role.id()))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + role.id() + "/delete"))
        .andExpect(status().isConflict());
  }
}
