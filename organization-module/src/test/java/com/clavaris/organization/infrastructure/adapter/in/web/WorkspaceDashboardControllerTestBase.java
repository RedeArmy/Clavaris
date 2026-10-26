package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Shared fixture for the Workspace dashboard controller tests: the same standalone MockMvc + real
 * Thymeleaf setup as {@link PlatformOrganizationDashboardControllerTest}, wiring all four
 * controllers of the family ({@link PlatformWorkspaceController} and its siblings) against the same
 * mocks, exactly as the application does, so each test class covers one controller's behaviour
 * without re-declaring the world.
 */
abstract class WorkspaceDashboardControllerTestBase {

  protected static final UUID OWNER_ID = UUID.randomUUID();

  protected GetOrganizationForPlatformAccountUseCase getOrganization;
  protected GetWorkspaceForOrganizationUseCase getWorkspace;
  protected ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  protected ListWorkspaceRolesForOrganizationUseCase listRoles;
  protected CreateWorkspaceUseCase createWorkspace;
  protected DeleteWorkspaceUseCase deleteWorkspace;
  protected CurrentPlatformAccountResolver currentPlatformAccount;
  protected ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  protected ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams;
  protected ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds;
  protected CreateWorkspaceTeamUseCase createTeam;
  protected RenameWorkspaceTeamUseCase renameTeam;
  protected DeleteWorkspaceTeamUseCase deleteTeam;
  protected AddRoleToWorkspaceTeamUseCase addRoleToTeam;
  protected CreateWorkspaceRoleUseCase createRole;
  protected DeleteWorkspaceRoleUseCase deleteRole;
  protected ListWorkspaceMembersUseCase listMembers;
  protected ListWorkspaceMembersPagedUseCase listMembersPaged;
  protected OrganizationAccountDirectory accountDirectory;
  protected AssignWorkspaceRoleToAccountUseCase assignRoleToAccount;
  protected ChangeWorkspaceMemberRoleUseCase changeMemberRole;
  protected MockMvc mockMvc;
  protected Organization organization;
  protected Workspace workspace;
  protected WorkspaceRole role;

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
    listTeamRoleIdsForTeams = mock(ListWorkspaceTeamRoleIdsForTeamsUseCase.class);
    listGroupedRoleIds = mock(ListGroupedWorkspaceRoleIdsUseCase.class);
    createTeam = mock(CreateWorkspaceTeamUseCase.class);
    renameTeam = mock(RenameWorkspaceTeamUseCase.class);
    deleteTeam = mock(DeleteWorkspaceTeamUseCase.class);
    addRoleToTeam = mock(AddRoleToWorkspaceTeamUseCase.class);
    createRole = mock(CreateWorkspaceRoleUseCase.class);
    deleteRole = mock(DeleteWorkspaceRoleUseCase.class);
    listMembers = mock(ListWorkspaceMembersUseCase.class);
    listMembersPaged = mock(ListWorkspaceMembersPagedUseCase.class);
    accountDirectory = mock(OrganizationAccountDirectory.class);
    assignRoleToAccount = mock(AssignWorkspaceRoleToAccountUseCase.class);
    changeMemberRole = mock(ChangeWorkspaceMemberRoleUseCase.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    workspace = Workspace.register(organization.id(), "Engineering");
    role = WorkspaceRole.defineReserved(organization.id(), "Admin");

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getWorkspace.handle(any())).thenReturn(Optional.of(workspace));
    when(listWorkspaces.handle(any())).thenReturn(emptyWorkspacesPage());
    when(listRoles.handle(any())).thenReturn(List.of(role));
    when(listTeams.handle(any())).thenReturn(List.of());
    stubTeamRoleIds(List.of());
    when(listGroupedRoleIds.handle(any())).thenReturn(Set.of());
    when(listMembers.handle(any())).thenReturn(List.of());
    when(listMembersPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(), null, null, false, false));
    when(accountDirectory.searchAccounts(any(), any(), anyInt())).thenReturn(List.of());
    when(accountDirectory.listAccountsByIds(any(), any())).thenReturn(List.of());

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

    WorkspaceDashboardSupport access =
        new WorkspaceDashboardSupport(
            getOrganization, getWorkspace, listRoles, currentPlatformAccount);
    WorkspaceTeamsModel teamsModel =
        new WorkspaceTeamsModel(listRoles, listTeams, listTeamRoleIdsForTeams, listGroupedRoleIds);
    WorkspaceHierarchyModel hierarchyModel =
        new WorkspaceHierarchyModel(teamsModel, listMembersPaged, accountDirectory);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformWorkspaceController(
                    access, teamsModel, listWorkspaces, createWorkspace, deleteWorkspace),
                new PlatformWorkspaceTeamController(
                    access, teamsModel, createTeam, renameTeam, deleteTeam, addRoleToTeam),
                new PlatformWorkspaceTeamRoleController(
                    access, teamsModel, createRole, deleteRole, addRoleToTeam),
                new PlatformWorkspaceHierarchyController(access, hierarchyModel, changeMemberRole),
                new PlatformWorkspaceAssignRoleController(
                    access, teamsModel, listMembers, accountDirectory, assignRoleToAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  protected String workspacesPath() {
    return "/platform/dashboard/organizations/" + organization.id() + "/workspaces";
  }

  protected String teamsPath() {
    return workspacesPath() + "/" + workspace.id() + "/teams";
  }

  protected String rolesPath() {
    return workspacesPath() + "/" + workspace.id() + "/roles";
  }

  private static KeysetPage<Workspace> emptyWorkspacesPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  // TD-PERF-030: every test here sets up exactly one WorkspaceTeam, so stubbing the same roleIds
  // for every team named in a given call's own query is equivalent to the old per-team
  // listTeamRoleIds.handle(any()) stub it replaces — never a hardcoded single teamId, so this
  // still works correctly if a future test adds a second team. doAnswer().when(...), not
  // when(...).thenAnswer(...): several tests call this twice (setUp's own default, then again to
  // override) — re-stubbing via when(mock.handle(any())) re-invokes the mock through any
  // already-registered stub as part of Mockito's own matcher-registration step, which would run
  // the FIRST call's answer lambda against a null argument and NPE; doAnswer never re-invokes the
  // mock, so it has no such side effect.
  protected void stubTeamRoleIds(final List<UUID> roleIds) {
    doAnswer(
            invocation -> {
              ListWorkspaceTeamRoleIdsForTeamsQuery query = invocation.getArgument(0);
              return query.teamIds().stream()
                  .collect(Collectors.toMap(teamId -> teamId, teamId -> roleIds));
            })
        .when(listTeamRoleIdsForTeams)
        .handle(any());
  }
}
