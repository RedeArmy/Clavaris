package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ListWorkspacesControllerTest {

  private ListWorkspacesForOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ListWorkspacesForOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ListWorkspacesController(useCase)).build();
  }

  @Test
  void returns200WithEveryWorkspace() throws Exception {
    UUID organizationId = UUID.randomUUID();
    Workspace workspace = Workspace.register(organizationId, "Engineering");
    when(useCase.handle(any())).thenReturn(List.of(workspace));

    mockMvc
        .perform(get("/api/v1/admin/organizations/" + organizationId + "/workspaces"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(workspace.id().toString()))
        .andExpect(jsonPath("$[0].name").value("Engineering"));
  }
}
