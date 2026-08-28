package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ListWorkspaceMembersControllerTest {

  private ListWorkspaceMembersUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ListWorkspaceMembersUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ListWorkspaceMembersController(useCase)).build();
  }

  @Test
  void returns200WithEveryMembership() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    WorkspaceMembership membership =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), WorkspaceRole.ADMIN);
    when(useCase.handle(any())).thenReturn(List.of(membership));

    mockMvc
        .perform(get("/api/v1/admin/workspaces/" + workspaceId + "/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].accountId").value(membership.accountId().toString()))
        .andExpect(jsonPath("$[0].role").value("ADMIN"));
  }
}
