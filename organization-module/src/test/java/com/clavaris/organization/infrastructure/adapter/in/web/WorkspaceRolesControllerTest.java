package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.CannotStripReservedWorkspaceRolePermissionsException;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.WorkspaceRoleCycleException;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceRolesControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private ListWorkspaceRolesForOrganizationUseCase listRoles;
  private CreateWorkspaceRoleUseCase createRole;
  private UpdateWorkspaceRoleUseCase updateRole;
  private DeleteWorkspaceRoleUseCase deleteRole;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    listRoles = mock(ListWorkspaceRolesForOrganizationUseCase.class);
    createRole = mock(CreateWorkspaceRoleUseCase.class);
    updateRole = mock(UpdateWorkspaceRoleUseCase.class);
    deleteRole = mock(DeleteWorkspaceRoleUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceRolesController(listRoles, createRole, updateRole, deleteRole))
            .build();
    organizationId = UUID.randomUUID();
  }

  private String path() {
    return "/api/v1/admin/organizations/" + organizationId + "/workspace-roles";
  }

  @Test
  void listReturns200WithEveryRole() throws Exception {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of("a"));
    when(listRoles.handle(any())).thenReturn(List.of(role));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Supervisor"));
  }

  @Test
  void createReturns201WithTheCreatedRole() throws Exception {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of("a"));
    when(createRole.handle(any())).thenReturn(role);

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supervisor\",\"permissions\":[\"a\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Supervisor"));
  }

  @Test
  void createRejectsABlankNameWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createRejectsANameOver255CharactersWithoutEverCallingTheUseCase() throws Exception {
    String tooLong = "a".repeat(256);

    mockMvc
        .perform(
            post(path())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verifyNoInteractions(createRole);
  }

  @Test
  void createReturns404WhenTheOrganizationDoesNotExist() throws Exception {
    when(createRole.handle(any())).thenThrow(new OrganizationNotFoundException(organizationId));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supervisor\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void createReturns404WhenTheParentRoleIdDoesNotExist() throws Exception {
    UUID unknownParentId = UUID.randomUUID();
    when(createRole.handle(any())).thenThrow(new WorkspaceRoleNotFoundException(unknownParentId));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supervisor\",\"parentRoleId\":\"" + unknownParentId + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void createReturns409WhenTheNameIsAlreadyTaken() throws Exception {
    when(createRole.handle(any())).thenThrow(new DuplicateWorkspaceRoleNameException("Supervisor"));

    mockMvc
        .perform(
            post(path())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supervisor\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void updateReturns200WithTheUpdatedRole() throws Exception {
    WorkspaceRole role = WorkspaceRole.define(organizationId, "Renamed", null, Set.of());
    when(updateRole.handle(any())).thenReturn(role);

    mockMvc
        .perform(
            patch(path() + "/" + role.id())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void updateReturns404WhenTheRoleDoesNotExist() throws Exception {
    UUID unknownRoleId = UUID.randomUUID();
    when(updateRole.handle(any())).thenThrow(new WorkspaceRoleNotFoundException(unknownRoleId));

    mockMvc
        .perform(
            patch(path() + "/" + unknownRoleId)
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateReturns409WhenTheNameIsAlreadyTaken() throws Exception {
    when(updateRole.handle(any())).thenThrow(new DuplicateWorkspaceRoleNameException("Taken"));

    mockMvc
        .perform(
            patch(path() + "/" + UUID.randomUUID())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Taken\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void updateReturns422WhenTheParentRoleIdWouldCreateACycle() throws Exception {
    UUID roleId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    when(updateRole.handle(any())).thenThrow(new WorkspaceRoleCycleException(roleId, parentId));

    mockMvc
        .perform(
            patch(path() + "/" + roleId)
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"parentRoleId\":\"" + parentId + "\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void updateRejectsANameOver255CharactersWithoutEverCallingTheUseCase() throws Exception {
    String tooLong = "a".repeat(256);

    mockMvc
        .perform(
            patch(path() + "/" + UUID.randomUUID())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verifyNoInteractions(updateRole);
  }

  @Test
  void updateReturns409WhenTheRequestWouldStripTheReservedRolesPermissions() throws Exception {
    when(updateRole.handle(any()))
        .thenThrow(new CannotStripReservedWorkspaceRolePermissionsException(UUID.randomUUID()));

    mockMvc
        .perform(
            patch(path() + "/" + UUID.randomUUID())
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Admin\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void deleteReturns204OnSuccess() throws Exception {
    mockMvc
        .perform(delete(path() + "/" + UUID.randomUUID()).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteReturns404WhenTheRoleDoesNotExist() throws Exception {
    UUID unknownRoleId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new WorkspaceRoleNotFoundException(unknownRoleId))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(delete(path() + "/" + unknownRoleId).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteReturns409WhenTheRoleIsReserved() throws Exception {
    UUID roleId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new CannotDeleteReservedWorkspaceRoleException(roleId))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(delete(path() + "/" + roleId).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }

  @Test
  void deleteReturns409WhenTheRoleIsStillAssigned() throws Exception {
    UUID roleId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new WorkspaceRoleStillAssignedException(roleId))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(delete(path() + "/" + roleId).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }

  @Test
  void deleteReturns409WhenTheRoleIsStillAnotherRolesParent() throws Exception {
    UUID roleId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new WorkspaceRoleHasChildRolesException(roleId))
        .when(deleteRole)
        .handle(any());

    mockMvc
        .perform(delete(path() + "/" + roleId).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
