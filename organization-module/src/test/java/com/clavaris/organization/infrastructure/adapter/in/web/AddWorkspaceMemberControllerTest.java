package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AddWorkspaceMemberControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private AddWorkspaceMemberUseCase useCase;
  private MockMvc mockMvc;
  private UUID workspaceId;

  @BeforeEach
  void setUp() {
    useCase = mock(AddWorkspaceMemberUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new AddWorkspaceMemberController(useCase)).build();
    workspaceId = UUID.randomUUID();
  }

  @Test
  void returns201WithTheCreatedMembership() throws Exception {
    WorkspaceMembership membership =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), WorkspaceRole.MEMBER);
    when(useCase.handle(any())).thenReturn(membership);

    mockMvc
        .perform(
            post("/api/v1/admin/workspaces/" + workspaceId + "/members")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accountId").value(membership.accountId().toString()))
        .andExpect(jsonPath("$.role").value("MEMBER"));
  }

  @Test
  void rejectsAMalformedEmailWithoutEverCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/workspaces/" + workspaceId + "/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns404WhenTheWorkspaceDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenThrow(new WorkspaceNotFoundException(workspaceId));

    mockMvc
        .perform(
            post("/api/v1/admin/workspaces/" + workspaceId + "/members")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.com\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheEmailIsAlreadyRegistered() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(
            new AccountProvisioner.AccountAlreadyExistsException(
                UUID.randomUUID(), "taken@example.com"));

    mockMvc
        .perform(
            post("/api/v1/admin/workspaces/" + workspaceId + "/members")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"taken@example.com\"}"))
        .andExpect(status().isConflict());
  }
}
