package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.removeworkspacemember.CannotRemoveLastAdminException;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMembershipNotFoundException;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RemoveWorkspaceMemberControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private RemoveWorkspaceMemberUseCase useCase;
  private MockMvc mockMvc;
  private UUID workspaceId;
  private UUID accountId;

  @BeforeEach
  void setUp() {
    useCase = mock(RemoveWorkspaceMemberUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new RemoveWorkspaceMemberController(useCase)).build();
    workspaceId = UUID.randomUUID();
    accountId = UUID.randomUUID();
  }

  private String path() {
    return "/api/v1/admin/workspaces/" + workspaceId + "/members/" + accountId + ":remove";
  }

  @Test
  void returns204OnSuccess() throws Exception {
    mockMvc
        .perform(post(path()).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns404WhenNoMembershipExists() throws Exception {
    doThrow(new WorkspaceMembershipNotFoundException(workspaceId, accountId))
        .when(useCase)
        .handle(any());

    mockMvc
        .perform(post(path()).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenThisWouldRemoveTheLastAdmin() throws Exception {
    doThrow(new CannotRemoveLastAdminException(workspaceId)).when(useCase).handle(any());

    mockMvc
        .perform(post(path()).principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isConflict());
  }
}
