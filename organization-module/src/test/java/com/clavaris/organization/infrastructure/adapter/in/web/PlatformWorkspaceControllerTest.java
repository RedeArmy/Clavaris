package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.RateLimitPolicySnapshot;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.CannotRemoveLastAdminException;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
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

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link
 * PlatformOrganizationDashboardControllerTest}/{@link PlatformOrganizationDetailControllerTest}.
 */
class PlatformWorkspaceControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetWorkspaceForOrganizationUseCase getWorkspace;
  private ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private ListWorkspaceMembersPagedUseCase listMembers;
  private CreateWorkspaceUseCase createWorkspace;
  private AddWorkspaceMemberUseCase addMember;
  private ChangeWorkspaceMemberRoleUseCase changeMemberRole;
  private RemoveWorkspaceMemberUseCase removeMember;
  private GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;
  private Workspace workspace;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getWorkspace = mock(GetWorkspaceForOrganizationUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationPagedUseCase.class);
    listMembers = mock(ListWorkspaceMembersPagedUseCase.class);
    createWorkspace = mock(CreateWorkspaceUseCase.class);
    addMember = mock(AddWorkspaceMemberUseCase.class);
    changeMemberRole = mock(ChangeWorkspaceMemberRoleUseCase.class);
    removeMember = mock(RemoveWorkspaceMemberUseCase.class);
    getRateLimitPolicy = mock(GetRateLimitPolicyForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    workspace = Workspace.register(organization.id(), "Engineering");

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getWorkspace.handle(any())).thenReturn(Optional.of(workspace));
    when(listWorkspaces.handle(any())).thenReturn(emptyWorkspacesPage());
    when(listMembers.handle(any())).thenReturn(emptyMembersPage());
    when(getRateLimitPolicy.handle(any()))
        .thenReturn(new RateLimitPolicySnapshot(600, false, null, 6000));

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
                    listMembers,
                    createWorkspace,
                    addMember,
                    changeMemberRole,
                    removeMember,
                    getRateLimitPolicy,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String workspacesPath() {
    return "/platform/dashboard/organizations/" + organization.id() + "/workspaces";
  }

  private String membersPath() {
    return workspacesPath() + "/" + workspace.id() + "/members";
  }

  private static KeysetPage<Workspace> emptyWorkspacesPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private static KeysetPage<WorkspaceMembership> emptyMembersPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private static KeysetCursor cursorOf(final WorkspaceMembership membership) {
    return new KeysetCursor(membership.createdAt(), membership.id());
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
  void showsTheWorkspaceAndItsMembers() throws Exception {
    WorkspaceMembership membership =
        WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), WorkspaceRole.ADMIN);
    KeysetCursor cursor = cursorOf(membership);
    when(listMembers.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(membership), cursor, cursor, false, false));

    mockMvc
        .perform(get(workspacesPath() + "/" + workspace.id()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("workspace", workspace))
        .andExpect(model().attribute("members", List.of(membership)));
  }

  // TD-PERF-020 (keyset revision): proves ?after= is actually decoded and threaded into the
  // query.
  @Test
  void getPassesTheAfterCursorThroughToTheMembersUseCase() throws Exception {
    KeysetCursor cursor = new KeysetCursor(java.time.Instant.now(), UUID.randomUUID());

    mockMvc.perform(get(workspacesPath() + "/" + workspace.id()).param("after", cursor.encode()));

    verify(listMembers)
        .handle(
            new ListWorkspaceMembersPagedQuery(workspace.id(), KeysetPageRequest.after(cursor)));
  }

  // TD-PERF-020: an HTMX-originated pagination link (hx-get) must get back just the members
  // fragment, not the full page — same reasoning
  // PlatformOrganizationDetailControllerTest's own identical test documents.
  @Test
  void htmxGetReturnsTheMembersFragmentInsteadOfTheFullPage() throws Exception {
    mockMvc
        .perform(get(workspacesPath() + "/" + workspace.id()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail :: members"));
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

  @Test
  void plainAddMemberPostRedirectsBackToTheWorkspacePage() throws Exception {
    when(addMember.handle(any()))
        .thenReturn(
            WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), WorkspaceRole.MEMBER));

    mockMvc
        .perform(post(membersPath()).param("email", "new@acme.example").param("role", "MEMBER"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(addMember).handle(any());
  }

  @Test
  void htmxAddMemberPostReturnsTheMembersFragment() throws Exception {
    when(addMember.handle(any()))
        .thenReturn(
            WorkspaceMembership.join(workspace.id(), UUID.randomUUID(), WorkspaceRole.MEMBER));

    mockMvc
        .perform(
            post(membersPath())
                .param("email", "new@acme.example")
                .param("role", "MEMBER")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail :: members"));
  }

  @Test
  void addMemberWithAnAlreadyRegisteredEmailRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    doThrow(
            new AccountProvisioner.AccountAlreadyExistsException(
                organization.id(), "dupe@acme.example"))
        .when(addMember)
        .handle(any());

    mockMvc
        .perform(post(membersPath()).param("email", "dupe@acme.example").param("role", "MEMBER"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("emailAlreadyRegisteredError", true));
  }

  @Test
  void plainChangeRolePostRedirectsOnSuccess() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(changeMemberRole.handle(any()))
        .thenReturn(WorkspaceMembership.join(workspace.id(), accountId, WorkspaceRole.ADMIN));

    mockMvc
        .perform(post(membersPath() + "/" + accountId + "/role").param("newRole", "ADMIN"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));
  }

  @Test
  void changeRoleThatWouldLeaveNoAdminRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new CannotDemoteLastAdminException(workspace.id()))
        .when(changeMemberRole)
        .handle(any());

    mockMvc
        .perform(post(membersPath() + "/" + accountId + "/role").param("newRole", "MEMBER"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("cannotDemoteLastAdminError", true));
  }

  @Test
  void plainRemoveMemberPostRedirectsOnSuccess() throws Exception {
    UUID accountId = UUID.randomUUID();

    mockMvc
        .perform(post(membersPath() + "/" + accountId + "/remove"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(workspacesPath() + "/" + workspace.id()));

    verify(removeMember).handle(any());
  }

  @Test
  void removeMemberThatWouldLeaveNoAdminRendersAnErrorInsteadOfPropagatingTheException()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    doThrow(new CannotRemoveLastAdminException(workspace.id())).when(removeMember).handle(any());

    mockMvc
        .perform(post(membersPath() + "/" + accountId + "/remove"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-detail"))
        .andExpect(model().attribute("cannotRemoveLastAdminError", true));
  }
}
