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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceQuery;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.CannotStripReservedWorkspaceRolePermissionsException;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.WorkspaceRoleCycleException;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Map;
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

/** Same standalone MockMvc + real Thymeleaf setup as {@link PlatformWorkspaceControllerTest}. */
class PlatformWorkspaceRoleControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private ListWorkspaceRolesForOrganizationUseCase listRoles;
  private ListWorkspacesForOrganizationUseCase listWorkspaces;
  private ListWorkspaceTeamsForWorkspaceUseCase listTeams;
  private ListWorkspaceTeamRoleIdsForTeamsUseCase listTeamRoleIdsForTeams;
  private CreateWorkspaceRoleUseCase createRole;
  private UpdateWorkspaceRoleUseCase updateRole;
  private DeleteWorkspaceRoleUseCase deleteRole;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;
  private WorkspaceRole reservedRole;
  private WorkspaceRole customRole;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    listRoles = mock(ListWorkspaceRolesForOrganizationUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationUseCase.class);
    listTeams = mock(ListWorkspaceTeamsForWorkspaceUseCase.class);
    listTeamRoleIdsForTeams = mock(ListWorkspaceTeamRoleIdsForTeamsUseCase.class);
    createRole = mock(CreateWorkspaceRoleUseCase.class);
    updateRole = mock(UpdateWorkspaceRoleUseCase.class);
    deleteRole = mock(DeleteWorkspaceRoleUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);
    reservedRole = WorkspaceRole.defineReserved(organization.id(), "Admin");
    customRole =
        WorkspaceRole.define(organization.id(), "Supervisor", null, Set.of("org:posts:create"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(listRoles.handle(any())).thenReturn(List.of(reservedRole, customRole));

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
                new PlatformWorkspaceRoleController(
                    getOrganization,
                    listRoles,
                    listWorkspaces,
                    listTeams,
                    listTeamRoleIdsForTeams,
                    createRole,
                    updateRole,
                    deleteRole,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String rolesPath() {
    return "/platform/dashboard/organizations/" + organization.id() + "/workspace-roles";
  }

  // Live UX request, 2026-10-02: restored alongside the Teams & Roles "Roles" section's own
  // unified table — this list stays the Configure-level, Workspace-independent view.
  @Test
  void showListRendersEveryRole() throws Exception {
    mockMvc
        .perform(get(rolesPath()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-roles"))
        .andExpect(
            model()
                .attribute(
                    "roles",
                    List.of(reservedRole, customRole).stream()
                        .sorted(java.util.Comparator.comparing(WorkspaceRole::name))
                        .toList()));
  }

  // Live UX request, 2026-10-02: a role with no team grouping anywhere still gets exactly one
  // row, with an empty Team cell — asserted on the rendered table itself (RoleRow is a private
  // record, not reachable from this test).
  @Test
  void showListRendersAnEmptyTeamCellForAnUngroupedRole() throws Exception {
    mockMvc
        .perform(get(rolesPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<th scope=\"col\">Team</th>")));
  }

  // Live UX request, 2026-10-02: ADR-0028 §2 only caps a role at one team PER Workspace — the
  // same role grouped into a different team in a different Workspace of this same Organization
  // appears as two separate rows, each naming that team and its own Workspace.
  @Test
  void showListExpandsARoleGroupedInTwoWorkspacesIntoTwoRows() throws Exception {
    Workspace workspaceA = Workspace.register(organization.id(), "Engineering");
    Workspace workspaceB = Workspace.register(organization.id(), "Product");
    when(listWorkspaces.handle(any())).thenReturn(List.of(workspaceA, workspaceB));

    WorkspaceTeam teamA = WorkspaceTeam.define(workspaceA.id(), "QA");
    WorkspaceTeam teamB = WorkspaceTeam.define(workspaceB.id(), "QA");
    when(listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspaceA.id())))
        .thenReturn(List.of(teamA));
    when(listTeams.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspaceB.id())))
        .thenReturn(List.of(teamB));
    when(listTeamRoleIdsForTeams.handle(
            new ListWorkspaceTeamRoleIdsForTeamsQuery(List.of(teamA.id()))))
        .thenReturn(Map.of(teamA.id(), List.of(customRole.id())));
    when(listTeamRoleIdsForTeams.handle(
            new ListWorkspaceTeamRoleIdsForTeamsQuery(List.of(teamB.id()))))
        .thenReturn(Map.of(teamB.id(), List.of(customRole.id())));

    mockMvc
        .perform(get(rolesPath()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("QA - Engineering")))
        .andExpect(content().string(containsString("QA - Product")));
  }

  @Test
  void showCreateFormRenders() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/create-workspace-role"));
  }

  // Live UX request, 2026-10-02: Back/Cancel default to this controller's own restored list when
  // no workspaceId is present — i.e. the page was opened from Configure, not Teams & Roles.
  @Test
  void showCreateFormWithNoWorkspaceIdDefaultsBackTargetToTheList() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/new"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("backTarget", rolesPath()))
        .andExpect(model().attribute("backLabel", "Back to Workspace Roles"));
  }

  // Live UX request, 2026-10-02: Back/Cancel return to Teams & Roles when "Advanced create" there
  // linked in with its own workspaceId.
  @Test
  void showCreateFormWithAWorkspaceIdPointsBackTargetAtTeamsAndRoles() throws Exception {
    UUID workspaceId = UUID.randomUUID();

    mockMvc
        .perform(get(rolesPath() + "/new").param("workspaceId", workspaceId.toString()))
        .andExpect(status().isOk())
        .andExpect(
            model()
                .attribute(
                    "backTarget",
                    "/platform/dashboard/organizations/"
                        + organization.id()
                        + "/workspaces/"
                        + workspaceId))
        .andExpect(model().attribute("backLabel", "Back to Teams & Roles"));
  }

  @Test
  void createPostRedirectsToTheListOnSuccess() throws Exception {
    mockMvc
        .perform(post(rolesPath()).param("name", "Interviewer").param("permissionsText", "a\nb"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(rolesPath()));

    verify(createRole).handle(any());
  }

  // Live UX request, 2026-10-02: workspaceId threaded through a validation-error redisplay (a
  // hidden form field, not just the GET query param) so Back/Cancel still returns to Teams & Roles
  // instead of silently falling back to the list.
  @Test
  void createPostWithADuplicateNameAndAWorkspaceIdKeepsBackTargetAtTeamsAndRoles()
      throws Exception {
    when(createRole.handle(any())).thenThrow(new DuplicateWorkspaceRoleNameException("Supervisor"));
    UUID workspaceId = UUID.randomUUID();

    mockMvc
        .perform(
            post(rolesPath())
                .param("name", "Supervisor")
                .param("workspaceId", workspaceId.toString()))
        .andExpect(status().isOk())
        .andExpect(
            model()
                .attribute(
                    "backTarget",
                    "/platform/dashboard/organizations/"
                        + organization.id()
                        + "/workspaces/"
                        + workspaceId));
  }

  @Test
  void createPostWithBlankNameReRendersWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post(rolesPath()).param("name", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/create-workspace-role"));

    verify(createRole, never()).handle(any());
  }

  @Test
  void createPostWithADuplicateNameReRendersWithAnError() throws Exception {
    when(createRole.handle(any())).thenThrow(new DuplicateWorkspaceRoleNameException("Supervisor"));

    mockMvc
        .perform(post(rolesPath()).param("name", "Supervisor"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/create-workspace-role"))
        .andExpect(model().attribute("duplicateNameError", true));
  }

  @Test
  void showDetailRendersTheRole() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/" + customRole.id()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-role-detail"))
        .andExpect(model().attribute("role", customRole))
        .andExpect(model().attribute("backTarget", rolesPath()));
  }

  @Test
  void showDetailWithAWorkspaceIdPointsBackTargetAtTeamsAndRoles() throws Exception {
    UUID workspaceId = UUID.randomUUID();

    mockMvc
        .perform(
            get(rolesPath() + "/" + customRole.id()).param("workspaceId", workspaceId.toString()))
        .andExpect(status().isOk())
        .andExpect(
            model()
                .attribute(
                    "backTarget",
                    "/platform/dashboard/organizations/"
                        + organization.id()
                        + "/workspaces/"
                        + workspaceId));
  }

  // The Workspace Role pages carry their own, more specific Back (to Configure > Workspace Roles or
  // to Teams & Roles); the generic "Back to Your Organizations" from the org tabs fragment must not
  // render beside it.
  @Test
  void showDetailRendersExactlyOneBackButton() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/" + customRole.id()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(containsString("Back to Your Organizations"))))
        .andExpect(
            result ->
                org.junit.jupiter.api.Assertions.assertEquals(
                    1,
                    result
                            .getResponse()
                            .getContentAsString()
                            .split("class=\"clavaris-back-button\"", -1)
                            .length
                        - 1));
  }

  @Test
  void showCreateFormRendersExactlyOneBackButton() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/new"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(containsString("Back to Your Organizations"))))
        .andExpect(
            result ->
                org.junit.jupiter.api.Assertions.assertEquals(
                    1,
                    result
                            .getResponse()
                            .getContentAsString()
                            .split("class=\"clavaris-back-button\"", -1)
                            .length
                        - 1));
  }

  @Test
  void showDetailReturns404ForARoleFromAnotherOrganization() throws Exception {
    mockMvc.perform(get(rolesPath() + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void updatePostRedirectsToTheDetailPageOnSuccess() throws Exception {
    WorkspaceRole updated = customRole.withName("Renamed");
    when(updateRole.handle(any())).thenReturn(updated);

    mockMvc
        .perform(post(rolesPath() + "/" + customRole.id()).param("name", "Renamed"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(rolesPath() + "/" + updated.id()));
  }

  @Test
  void updatePostWithADuplicateNameReRendersTheDetailPageWithAnError() throws Exception {
    when(updateRole.handle(any())).thenThrow(new DuplicateWorkspaceRoleNameException("Taken"));

    mockMvc
        .perform(post(rolesPath() + "/" + customRole.id()).param("name", "Taken"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-role-detail"))
        .andExpect(model().attribute("duplicateNameError", true));
  }

  @Test
  void updatePostThatWouldCreateACycleReRendersTheDetailPageWithAnError() throws Exception {
    when(updateRole.handle(any()))
        .thenThrow(new WorkspaceRoleCycleException(customRole.id(), reservedRole.id()));

    mockMvc
        .perform(
            post(rolesPath() + "/" + customRole.id())
                .param("name", "Supervisor")
                .param("parentRoleId", reservedRole.id().toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-role-detail"))
        .andExpect(model().attribute("parentCycleError", true));
  }

  @Test
  void updatePostThatWouldStripTheReservedRolesPermissionsReturns400() throws Exception {
    when(updateRole.handle(any()))
        .thenThrow(new CannotStripReservedWorkspaceRolePermissionsException(reservedRole.id()));

    mockMvc
        .perform(post(rolesPath() + "/" + reservedRole.id()).param("name", "Admin"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updatePostForAnUnknownRoleReturns404() throws Exception {
    mockMvc
        .perform(post(rolesPath() + "/" + UUID.randomUUID()).param("name", "Anything"))
        .andExpect(status().isNotFound());

    verify(updateRole, never()).handle(any());
  }

  @Test
  void deletePostRedirectsToTheListOnSuccess() throws Exception {
    mockMvc
        .perform(post(rolesPath() + "/" + customRole.id() + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(rolesPath()));

    verify(deleteRole).handle(any());
  }

  @Test
  void deletePostWhileStillAssignedReRendersTheDetailPageWithAnError() throws Exception {
    doThrow(new WorkspaceRoleStillAssignedException(customRole.id()))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + customRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-role-detail"))
        .andExpect(model().attribute("stillAssignedError", true));
  }

  @Test
  void deletePostWhileStillAParentReRendersTheDetailPageWithAnError() throws Exception {
    doThrow(new WorkspaceRoleHasChildRolesException(customRole.id()))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + customRole.id() + "/delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/workspace-role-detail"))
        .andExpect(model().attribute("hasChildRolesError", true));
  }

  @Test
  void deletePostForAReservedRoleReturns409() throws Exception {
    doThrow(new CannotDeleteReservedWorkspaceRoleException(reservedRole.id()))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(post(rolesPath() + "/" + reservedRole.id() + "/delete"))
        .andExpect(status().isConflict());
  }

  @Test
  void deletePostForAnUnknownRoleReturns404() throws Exception {
    mockMvc
        .perform(post(rolesPath() + "/" + UUID.randomUUID() + "/delete"))
        .andExpect(status().isNotFound());

    verify(deleteRole, never()).handle(any());
  }

  @Test
  void createPostWithATamperedParentRoleIdReturns400() throws Exception {
    when(createRole.handle(any())).thenThrow(new WorkspaceRoleNotFoundException(UUID.randomUUID()));

    mockMvc
        .perform(post(rolesPath()).param("name", "Interviewer"))
        .andExpect(status().isBadRequest());
  }
}
