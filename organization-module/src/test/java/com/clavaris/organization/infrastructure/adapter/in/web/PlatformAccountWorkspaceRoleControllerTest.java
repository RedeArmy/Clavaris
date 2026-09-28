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

import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
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
  private ListWorkspacesForOrganizationUseCase listWorkspaces;
  private ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private ListWorkspaceTeamRoleIdsUseCase listTeamRoleIds;
  private ListWorkspaceRolesForOrganizationUseCase listRoles;
  private AssignWorkspaceRoleToAccountUseCase assignRoleToAccount;
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
    listWorkspaces = mock(ListWorkspacesForOrganizationUseCase.class);
    listTeams = mock(ListWorkspaceTeamsForWorkspaceUseCase.class);
    listTeamRoleIds = mock(ListWorkspaceTeamRoleIdsUseCase.class);
    listRoles = mock(ListWorkspaceRolesForOrganizationUseCase.class);
    assignRoleToAccount = mock(AssignWorkspaceRoleToAccountUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    workspace = Workspace.register(organization.id(), "Engineering");
    role = WorkspaceRole.defineReserved(organization.id(), "Admin");
    accountId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getWorkspace.handle(any())).thenReturn(Optional.of(workspace));
    when(listWorkspaces.handle(any())).thenReturn(List.of(workspace));
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
                    listWorkspaces,
                    listTeams,
                    listTeamRoleIds,
                    listRoles,
                    assignRoleToAccount,
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
  void showsTheRoleFormWhenTheAccountBelongsToAWorkspace() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(role.name())));
  }

  // SDE-III review, 2026-09-27: findMembership resolves by accountId alone, with no
  // organizationId filter of its own (see that use case's own Javadoc) — this is the real,
  // previously-untested cross-tenant scenario that check exists to reject: an accountId whose
  // real workspace membership belongs to an Organization the caller doesn't own.
  @Test
  void returnsNotFoundWhenTheMembershipsWorkspaceBelongsToAnotherOrganization() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(assignRolePath())).andExpect(status().isNotFound());
  }

  @Test
  void showsTheTeamSelectorOnlyWhenAtLeastTwoBucketsQualify() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of(role.id()));

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("QA")));
  }

  // SDE-III addition, 2026-09-27: a team with zero roles doesn't count/show at all — same rule
  // this controller's own Javadoc documents.
  @Test
  void hidesAnEmptyTeamFromTheSelector() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, null);
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    WorkspaceTeam emptyTeam = WorkspaceTeam.define(workspace.id(), "EmptyTeam");
    when(listTeams.handle(any())).thenReturn(List.of(emptyTeam));
    when(listTeamRoleIds.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("EmptyTeam"))));
  }

  @Test
  void postSavesTheNewRoleAndTriggersTheRefreshEvent() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    when(assignRoleToAccount.handle(any())).thenReturn(membership.withRoleId(role.id()));

    mockMvc
        .perform(
            post(assignRolePath())
                .param("workspaceId", workspace.id().toString())
                .param("newRoleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Trigger", "workspace-role-assigned"));

    verify(assignRoleToAccount).handle(any());
  }

  @Test
  void postWithCannotDemoteLastAdminReRendersTheFormInsteadOfPropagatingTheException()
      throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    doThrow(new CannotDemoteLastAdminException(workspace.id()))
        .when(assignRoleToAccount)
        .handle(any());

    mockMvc
        .perform(
            post(assignRolePath())
                .param("workspaceId", workspace.id().toString())
                .param("newRoleId", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("HX-Trigger"));
  }

  // SDE-III redesign, 2026-09-27 ("Way 2"): this used to 404 — the whole point of this redesign is
  // that a roleless account (no membership yet) can now be assigned a role directly, originating
  // its membership rather than requiring one to already exist.
  @Test
  void postWhenTheAccountHasNoMembershipCreatesOneInsteadOfReturningNotFound() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    when(assignRoleToAccount.handle(any()))
        .thenReturn(WorkspaceMembership.join(workspace.id(), accountId, role.id()));

    mockMvc
        .perform(
            post(assignRolePath())
                .param("workspaceId", workspace.id().toString())
                .param("newRoleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Trigger", "workspace-role-assigned"));

    verify(assignRoleToAccount).handle(any());
  }

  @Test
  void postWithASubmittedWorkspaceNotOwnedByThisOrganizationReturnsNotFound() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post(assignRolePath())
                .param("workspaceId", UUID.randomUUID().toString())
                .param("newRoleId", role.id().toString()))
        .andExpect(status().isNotFound());

    verify(assignRoleToAccount, never()).handle(any());
  }

  // Anti-tampering: the existing membership's own workspaceId is the only value this popup ever
  // renders (hidden field) for an already-a-member account — a mismatch means the submitted value
  // was tampered with.
  @Test
  void postWithAWorkspaceIdMismatchingAnExistingMembershipReturnsBadRequest() throws Exception {
    WorkspaceMembership membership = WorkspaceMembership.join(workspace.id(), accountId, role.id());
    when(findMembership.handle(any())).thenReturn(Optional.of(membership));
    Workspace otherWorkspace = Workspace.register(organization.id(), "Support");
    when(getWorkspace.handle(any())).thenReturn(Optional.of(otherWorkspace));

    mockMvc
        .perform(
            post(assignRolePath())
                .param("workspaceId", otherWorkspace.id().toString())
                .param("newRoleId", role.id().toString()))
        .andExpect(status().isBadRequest());

    verify(assignRoleToAccount, never()).handle(any());
  }

  @Test
  void postRendersNotFoundWhenTheAccountDoesNotBelongToThisOrganization() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    doThrow(new AccountNotInOrganizationException(accountId, organization.id()))
        .when(assignRoleToAccount)
        .handle(any());

    mockMvc
        .perform(
            post(assignRolePath())
                .param("workspaceId", workspace.id().toString())
                .param("newRoleId", role.id().toString()))
        .andExpect(status().isNotFound());
  }

  // SDE-III addition, 2026-09-27: the Workspace selector's own visibility rules.
  @Test
  void showsAnEmptyStateWhenTheOrganizationHasNoWorkspacesYet() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    when(listWorkspaces.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("no Workspaces yet")));
  }

  @Test
  void hidesTheWorkspaceSelectorWhenOnlyOneWorkspaceExists() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.not(containsString("assignRoleWorkspaceId"))));
  }

  @Test
  void showsTheWorkspaceSelectorWhenMultipleWorkspacesExist() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    Workspace secondWorkspace = Workspace.register(organization.id(), "Support");
    when(listWorkspaces.handle(any())).thenReturn(List.of(workspace, secondWorkspace));

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("assignRoleWorkspaceId")))
        .andExpect(content().string(containsString("Support")));
  }

  @Test
  void showsAnEmptyStateWhenTheActiveWorkspaceHasNoRolesYet() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    when(listRoles.handle(any())).thenReturn(List.of());

    mockMvc
        .perform(get(assignRolePath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("no roles yet")));
  }

  @Test
  void aWorkspaceQueryParamNotBelongingToThisOrganizationIsIgnored() throws Exception {
    when(findMembership.handle(any())).thenReturn(Optional.empty());
    Workspace secondWorkspace = Workspace.register(organization.id(), "Support");
    when(listWorkspaces.handle(any())).thenReturn(List.of(workspace, secondWorkspace));

    mockMvc
        .perform(get(assignRolePath()).param("workspaceId", UUID.randomUUID().toString()))
        .andExpect(status().isOk());
  }
}
