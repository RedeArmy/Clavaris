package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.WorkspaceRoleAlreadyInAnotherTeamException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AccountNotInOrganizationException;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.WorkspaceMembershipNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamCommand;
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
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  private ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams;
  private ListGroupedWorkspaceRoleIdsUseCase listGroupedRoleIds;
  private CreateWorkspaceTeamUseCase createTeam;
  private RenameWorkspaceTeamUseCase renameTeam;
  private DeleteWorkspaceTeamUseCase deleteTeam;
  private AddRoleToWorkspaceTeamUseCase addRoleToTeam;
  private CreateWorkspaceRoleUseCase createRole;
  private DeleteWorkspaceRoleUseCase deleteRole;
  private ListWorkspaceMembersUseCase listMembers;
  private ListWorkspaceMembersPagedUseCase listMembersPaged;
  private OrganizationAccountDirectory accountDirectory;
  private AssignWorkspaceRoleToAccountUseCase assignRoleToAccount;
  private ChangeWorkspaceMemberRoleUseCase changeMemberRole;
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
    when(accountDirectory.listAccountsForOrganization(any())).thenReturn(List.of());
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
                    listTeamRoleIdsForTeams,
                    listGroupedRoleIds,
                    createTeam,
                    renameTeam,
                    deleteTeam,
                    addRoleToTeam,
                    createRole,
                    deleteRole,
                    listMembers,
                    listMembersPaged,
                    accountDirectory,
                    assignRoleToAccount,
                    changeMemberRole))
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

  // TD-PERF-030: every test here sets up exactly one WorkspaceTeam, so stubbing the same roleIds
  // for every team named in a given call's own query is equivalent to the old per-team
  // listTeamRoleIds.handle(any()) stub it replaces — never a hardcoded single teamId, so this
  // still works correctly if a future test adds a second team. doAnswer().when(...), not
  // when(...).thenAnswer(...): several tests call this twice (setUp's own default, then again to
  // override) — re-stubbing via when(mock.handle(any())) re-invokes the mock through any
  // already-registered stub as part of Mockito's own matcher-registration step, which would run
  // the FIRST call's answer lambda against a null argument and NPE; doAnswer never re-invokes the
  // mock, so it has no such side effect.
  private void stubTeamRoleIds(final List<UUID> roleIds) {
    doAnswer(
            invocation -> {
              ListWorkspaceTeamRoleIdsForTeamsQuery query = invocation.getArgument(0);
              return query.teamIds().stream()
                  .collect(Collectors.toMap(teamId -> teamId, teamId -> roleIds));
            })
        .when(listTeamRoleIdsForTeams)
        .handle(any());
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
        .andExpect(content().string(containsString("clavaris-back-button")))
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
    stubTeamRoleIds(List.of(role.id()));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"))
        .andExpect(model().attribute("teams", List.of(team)));
  }

  // Live UX request, 2026-10-02: team-level search/pagination markup (teams-hierarchy-filter.js)
  // and each role as a <details>/<summary> disclosure carrying its own member count.
  @Test
  void rendersTeamSearchMarkupAndEachRolesOwnMemberCount() throws Exception {
    WorkspaceTeam team = WorkspaceTeam.define(workspace.id(), "QA");
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-teams-hierarchy")))
        .andExpect(content().string(containsString("data-teams-search")))
        .andExpect(content().string(containsString("data-team-row")))
        .andExpect(content().string(containsString("data-team-name=\"QA\"")))
        .andExpect(content().string(containsString(role.name() + " (0)")));
  }

  // Live UX request, 2026-09-28: a bare accountId used to render here — real gap, closed via
  // OrganizationAccountDirectory (same cross-module port the "Assign role" picker already uses).
  @Test
  void showsEveryMemberHoldingARoleInTheHierarchyByNameNotId() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())),
                null,
                null,
                false,
                false));
    when(accountDirectory.listAccountsByIds(any(), any()))
        .thenReturn(List.of(new OrganizationAccountSummary(accountId, "Jane Doe")));

    // The raw accountId still legitimately appears in the "Remove" button's own form action URL
    // (live UX request, 2026-09-28) — only the DISPLAYED label is asserted here, not "the id
    // appears nowhere on the page at all."
    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Jane Doe")));
  }

  // Live UX request, 2026-10-02: "Remove" used to be a bare single-click submit for every member
  // except the one edge case already gated behind a confirm — now every member's own Remove opens
  // a real confirm popup first, same TD-FUT-035 shape Delete team/Delete role already use.
  @Test
  void rendersAConfirmPopupForEveryMembersOwnRemoveButton() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())),
                null,
                null,
                false,
                false));
    when(accountDirectory.listAccountsByIds(any(), any()))
        .thenReturn(List.of(new OrganizationAccountSummary(accountId, "Jane Doe")));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(containsString("data-dialog-open=\"remove-member-dialog-" + accountId)))
        .andExpect(
            content().string(containsString("id=\"remove-member-dialog-" + accountId + "\"")));
  }

  // Defensive only — every real accountId here comes from a WorkspaceMembership, which is only
  // ever created against an account the directory already returned
  // (AccountNotInOrganizationException
  // guards that at creation time). Covers the fallback itself, not a reachable production gap.
  @Test
  void fallsBackToTheRawAccountIdWhenItIsMissingFromTheDirectory() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), accountId, role.id())),
                null,
                null,
                false,
                false));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(accountId.toString())));
  }

  // ADR-0027 §5: a membership can be roleless — that account must not blow up the hierarchy's own
  // per-role grouping (its accountId simply never appears under any role).
  @Test
  void doesNotBlowUpOnARolelessMembership() throws Exception {
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), null)),
                null,
                null,
                false,
                false));

    mockMvc.perform(get(teamsPath())).andExpect(status().isOk());
  }

  // TD-PERF-027: the pagination nav must actually render (and only) when there's a real next/
  // previous page — proves the fix isn't cosmetic (a membersPage model attribute nobody reads).
  @Test
  void rendersTheNextLinkWhenAFurtherPageOfMembersExists() throws Exception {
    KeysetCursor cursor = new KeysetCursor(Instant.now(), UUID.randomUUID());
    when(listMembersPaged.handle(any()))
        .thenReturn(
            new KeysetPage<>(
                List.of(WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), role.id())),
                cursor,
                cursor,
                true,
                false));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("nav class=\"clavaris-pagination\"")))
        .andExpect(content().string(containsString(">Next<")))
        .andExpect(content().string(not(containsString(">Previous<"))));
  }

  @Test
  void rendersNoPaginationNavWhenEverythingFitsOnOnePage() throws Exception {
    when(listMembersPaged.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(), null, null, false, false));

    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("nav class=\"clavaris-pagination\""))));
  }

  @Test
  void groupsAnUngroupedRoleUnderWithoutTeamInTheHierarchy() throws Exception {
    mockMvc
        .perform(get(teamsPath()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("ungroupedRoles", List.of(role)))
        .andExpect(content().string(containsString("Without Team")));
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

  // Live UX request, 2026-09-28: "Remove" next to each member in the Teams hierarchy.
  @Test
  void plainUnassignMemberFromRolePostRendersTheHierarchyPage() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"));

    verify(changeMemberRole).handle(any());
  }

  @Test
  void htmxUnassignMemberFromRolePostReturnsTheHierarchyFragment() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy :: hierarchy"));

    verify(changeMemberRole).handle(any());
  }

  @Test
  void unassignMemberFromRoleReturnsNotFoundForAnUnknownMembership() throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new WorkspaceMembershipNotFoundException(workspace.id(), accountId))
        .when(changeMemberRole)
        .handle(any());

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unassignMemberFromRoleRendersAnErrorWhenItWouldLeaveNoManageMembersHolder()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new CannotDemoteLastAdminException(workspace.id()))
        .when(changeMemberRole)
        .handle(any());

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true))
        .andExpect(model().attribute("forceConfirmAccountId", accountId));
  }

  // Live UX request, 2026-09-29: typing "unassign" to confirm bypasses ManageMembersGuard —
  // the Clavaris platform dashboard is a higher trust tier than that guard is meant to protect.
  @Test
  void unassignMemberFromRoleWithConfirmedActionForcesTheChange() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(workspacesPath()
                    + "/"
                    + workspace.id()
                    + "/members/"
                    + accountId
                    + "/unassign-role")
                .param("confirmedAction", "unassign"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-teams-hierarchy"));

    ArgumentCaptor<ChangeWorkspaceMemberRoleCommand> captured =
        ArgumentCaptor.forClass(ChangeWorkspaceMemberRoleCommand.class);
    verify(changeMemberRole).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }

  @Test
  void unassignMemberFromRoleReturnsNotFoundWhenTheWorkspaceDoesNotBelongToTheOrganization()
      throws Exception {
    when(getWorkspace.handle(any())).thenReturn(Optional.empty());
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(
            post(
                workspacesPath()
                    + "/"
                    + UUID.randomUUID()
                    + "/members/"
                    + accountId
                    + "/unassign-role"))
        .andExpect(status().isNotFound());

    verify(changeMemberRole, never()).handle(any());
  }

  // SDE-III addition, 2026-09-27: "Assign role" popup scoped to one specific team (Way 1 of the
  // live UX request — the Teams tab's own per-team/per-"No team" trigger).
  @Test
  void showsEveryOrganizationAccountNotAlreadyInThisTeamAsEligible() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
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

  // Live UX request, 2026-10-02: one role at a time, workspace-wide — an account already holding a
  // role in a DIFFERENT team (not just one already holding a role in THIS team) is excluded too.
  @Test
  void excludesAnAccountAlreadyHoldingARoleInADifferentTeamAsNotEligible() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
    OrganizationAccountSummary eligible =
        new OrganizationAccountSummary(UUID.randomUUID(), "eligible@example.com");
    OrganizationAccountSummary inAnotherTeam =
        new OrganizationAccountSummary(UUID.randomUUID(), "elsewhere@example.com");
    WorkspaceRole otherTeamRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());
    when(accountDirectory.listAccountsForOrganization(organization.id()))
        .thenReturn(List.of(eligible, inAnotherTeam));
    when(listMembers.handle(any()))
        .thenReturn(
            List.of(
                WorkspaceMembership.join(
                    workspace.id(), inAnotherTeam.accountId(), otherTeamRole.id())));

    mockMvc
        .perform(get(teamsPath() + "/" + teamId + "/assign-role"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("eligibleAccounts", List.of(eligible)));
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
    stubTeamRoleIds(List.of(role.id()));
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
    stubTeamRoleIds(List.of(role.id()));

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
    stubTeamRoleIds(List.of());
    WorkspaceRole otherRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", UUID.randomUUID().toString())
                .param("roleId", otherRole.id().toString()))
        .andExpect(status().isBadRequest());

    verify(assignRoleToAccount, never()).handle(any());
  }

  // Live UX request, 2026-10-02: defense in depth — the popup's own User <select> already
  // excludes an account like this one, so reaching this normally means a stale form (someone else
  // assigned the account a role elsewhere while the popup was still open), not tampering; re-
  // rendered as a form error rather than a raw 400.
  @Test
  void assignRoleForTeamPostRejectsAnAccountThatAlreadyHasARoleElsewhereInThisWorkspace()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.reconstitute(teamId, workspace.id(), "QA", Instant.now());
    when(listTeams.handle(any())).thenReturn(List.of(team));
    stubTeamRoleIds(List.of(role.id()));
    UUID accountId = UUID.randomUUID();
    WorkspaceRole otherRole = WorkspaceRole.define(organization.id(), "Other", null, Set.of());
    when(listMembers.handle(any()))
        .thenReturn(List.of(WorkspaceMembership.join(workspace.id(), accountId, otherRole.id())));

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/assign-role")
                .param("accountId", accountId.toString())
                .param("roleId", role.id().toString()))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/fragments/team-assign-role-form :: assignRoleForm"))
        .andExpect(model().attribute("accountAlreadyHasRoleError", true));

    verify(assignRoleToAccount, never()).handle(any());
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

  // Live UX request, 2026-09-29: unlike deleteRole above, this aborts the WHOLE team deletion
  // (DeleteWorkspaceTeamService's own Javadoc explains why) — same "type UNASSIGN to confirm"
  // popup, this time retrying the entire delete, not just one role's own unassign.
  @Test
  void deleteTeamThatWouldLeaveNoManageMembersHolderRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new CannotDemoteLastAdminException(workspace.id())).when(deleteTeam).handle(any());

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true))
        .andExpect(model().attribute("forceConfirmTeamId", teamId));
  }

  @Test
  void deleteTeamWithConfirmedActionForcesTheDeletion() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/delete").param("confirmedAction", "unassign"))
        .andExpect(status().is3xxRedirection());

    ArgumentCaptor<DeleteWorkspaceTeamCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceTeamCommand.class);
    verify(deleteTeam).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }

  @Test
  void plainAddRoleToTeamPostRedirectsOnSuccess() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles").param("roleIds", role.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam).handle(any());
  }

  // Live UX request, 2026-10-01: the picker popup is multi-select — one submit carries several
  // roleIds, each added through its own AddRoleToWorkspaceTeamUseCase#handle call.
  @Test
  void addRoleToTeamPostWithMultipleRoleIdsHandlesEachOneIndividually() throws Exception {
    UUID teamId = UUID.randomUUID();
    WorkspaceRole secondRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());

    mockMvc
        .perform(
            post(teamsPath() + "/" + teamId + "/roles")
                .param("roleIds", role.id().toString(), secondRole.id().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam, times(2)).handle(any());
  }

  @Test
  void addRoleToTeamPostWithNoRoleIdsSelectedIsANoOp() throws Exception {
    UUID teamId = UUID.randomUUID();

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addRoleToTeam, never()).handle(any());
  }

  @Test
  void addRoleToTeamAlreadyInAnotherTeamRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID teamId = UUID.randomUUID();
    doThrow(new WorkspaceRoleAlreadyInAnotherTeamException(role.id(), UUID.randomUUID()))
        .when(addRoleToTeam)
        .handle(any());

    mockMvc
        .perform(post(teamsPath() + "/" + teamId + "/roles").param("roleIds", role.id().toString()))
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

  // Live UX request, 2026-09-28: "Without Team" is now a first-class choice in the popup itself,
  // not just something a role ends up in after its team is later deleted.
  @Test
  void plainCreateRolePostWithoutATeamCreatesAnUngroupedRole() throws Exception {
    WorkspaceRole created = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(createRole.handle(any())).thenReturn(created);

    mockMvc
        .perform(post(rolesPath()).param("name", "Reviewer").param("teamId", ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(createRole).handle(any());
    verify(addRoleToTeam, never()).handle(any());
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

  // Live UX request, 2026-09-28: deleting a role now auto-unassigns every holder in this
  // Workspace first — this is that bulk unassign hitting ManageMembersGuard.
  @Test
  void deleteRoleThatWouldLeaveNoManageMembersHolderRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));
    doThrow(new CannotDemoteLastAdminException(workspace.id())).when(deleteRole).handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + plainRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true))
        .andExpect(model().attribute("forceConfirmRoleId", plainRole.id()));
  }

  // Live UX request, 2026-09-29: typing "unassign" to confirm bypasses ManageMembersGuard —
  // the Clavaris platform dashboard is a higher trust tier than that guard is meant to protect.
  @Test
  void deleteRoleWithConfirmedActionForcesTheDeletion() throws Exception {
    WorkspaceRole plainRole = WorkspaceRole.define(organization.id(), "Reviewer", null, Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(role, plainRole));

    mockMvc
        .perform(
            post(rolesPath() + "/" + plainRole.id() + "/delete")
                .param("confirmedAction", "unassign"))
        .andExpect(status().is3xxRedirection());

    ArgumentCaptor<DeleteWorkspaceRoleCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceRoleCommand.class);
    verify(deleteRole).handle(captured.capture());
    assertThat(captured.getValue().force()).isTrue();
  }
}
