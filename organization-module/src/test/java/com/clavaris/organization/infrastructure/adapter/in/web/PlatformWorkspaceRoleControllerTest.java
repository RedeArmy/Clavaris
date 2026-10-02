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

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.CannotStripReservedWorkspaceRolePermissionsException;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.WorkspaceRoleCycleException;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.WorkspaceRole;
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

/** Same standalone MockMvc + real Thymeleaf setup as {@link PlatformWorkspaceControllerTest}. */
class PlatformWorkspaceRoleControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private ListWorkspaceRolesForOrganizationUseCase listRoles;
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

  @Test
  void showCreateFormRenders() throws Exception {
    mockMvc
        .perform(get(rolesPath() + "/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/create-workspace-role"));
  }

  // Live UX request, 2026-10-02: the list page this used to redirect to is gone (Teams & Roles'
  // own unified table is the new entry point) — redirects to the newly created role's own detail
  // page instead, a self-contained destination that needs no further list to bounce through.
  @Test
  void createPostRedirectsToTheNewRolesDetailPageOnSuccess() throws Exception {
    when(createRole.handle(any())).thenReturn(customRole);

    mockMvc
        .perform(post(rolesPath()).param("name", "Interviewer").param("permissionsText", "a\nb"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(rolesPath() + "/" + customRole.id()));

    verify(createRole).handle(any());
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
        .andExpect(model().attribute("role", customRole));
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

  // Live UX request, 2026-10-02: the list page this used to redirect to is gone — there's no
  // single Workspace this Organization-scoped controller could redirect into instead (a
  // WorkspaceRole isn't owned by any one Workspace), so the Organization's own detail page is the
  // nearest always-valid destination.
  @Test
  void deletePostRedirectsToTheOrganizationDetailPageOnSuccess() throws Exception {
    mockMvc
        .perform(post(rolesPath() + "/" + customRole.id() + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + organization.id()));

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
