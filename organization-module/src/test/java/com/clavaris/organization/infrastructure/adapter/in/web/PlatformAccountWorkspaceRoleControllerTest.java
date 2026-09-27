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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc + real Thymeleaf setup as {@link PlatformWorkspaceControllerTest}. */
class PlatformAccountWorkspaceRoleControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetWorkspaceForOrganizationUseCase getWorkspace;
  private FindWorkspaceMembershipForAccountUseCase findMembership;
  private ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private ListWorkspaceRolesForOrganizationUseCase listRoles;
  private ChangeWorkspaceMemberRoleUseCase changeMemberRole;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;
  private Workspace workspace;
  private WorkspaceRole role;
  private UUID accountId;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getWorkspace = mock(GetWorkspaceForOrganizationUseCase.class);
    findMembership = mock(FindWorkspaceMembershipForAccountUseCase.class);
    listTeams = mock(ListWorkspaceTeamsForWorkspaceUseCase.class);
    listTeamRoleIds = mock(ListWorkspaceTeamRoleIdsUseCase.class);
    listRoles = mock(ListWorkspaceRolesForOrganizationUseCase.class);
    changeMemberRole = mock(ChangeWorkspaceMemberRoleUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    workspace = Workspace.register(organization.id(), "Engineering");
    role = WorkspaceRole.defineReserved(organization.id(), "Admin");
    accountId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getWorkspace.handle(any())).thenReturn(Optional.of(workspace));
    when(listTeams.handle(any())).thenReturn(List.of());
    when(listTeamRoleIds.handle(any())).thenReturn(List.of());
    when(listRoles.handle(any())).thenReturn(List.of(role));

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
                new PlatformAccountWorkspaceRoleController(
                    getOrganization,
                    getWorkspace,
                    findMembership,
                    listTeams,
                    listTeamRoleIds,
                    listRoles,
                    changeMemberRole,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String assignRolePath() {
    return "/platform/dashboard/organizations/"
        + organization.id()
        + "/accounts/"
        + accountId
        + "/assign-role";
  }

  @Test
  void showsAnEmptyStateWhenTheAccountHasNoWorkspaceMembership() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("any Workspace yet")));
  }

  @Test
  void showsTheRoleFormWhenTheAccountBelongsToAWorkspace() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(role.name())));
  }

  @Test
  void showsTheTeamSelectorOnlyWhenTeamsExist() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("QA")));
  }

  @Test
  void postSavesTheNewRoleAndTriggersTheRefreshEvent() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    when(changeMemberRole.handle(any())).thenReturn(membership.withRoleId(role.id()));

    mockMvc
        .perform(post(assignRolePath()).param("newRoleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Trigger", "workspace-role-assigned"));

    verify(changeMemberRole).handle(any());
  }

  @Test
  void postWithCannotDemoteLastAdminReRendersTheFormInsteadOfPropagatingTheException()
      throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    doThrow(new CannotDemoteLastAdminException(workspace.id()))
        .when(changeMemberRole)
        .handle(any());

    mockMvc
        .perform(post(assignRolePath()).param("newRoleId", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("HX-Trigger"));
  }

  @Test
  void postWhenTheAccountHasNoMembershipReturnsNotFound() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(assignRolePath()).param("newRoleId", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());

    verify(changeMemberRole, never()).handle(any());
  }
}
