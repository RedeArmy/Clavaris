package com.clavaris.organization.application.usecase.listworkspacesfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWorkspacesForOrganizationServiceTest {

  @Test
  void returnsEveryWorkspaceTheRepositoryReturns() {
    WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    UUID organizationId = UUID.randomUUID();
    Workspace workspace = Workspace.register(organizationId, "Engineering");
    when(workspaces.findAllByOrganizationId(organizationId)).thenReturn(List.of(workspace));
    ListWorkspacesForOrganizationService service =
        new ListWorkspacesForOrganizationService(workspaces);

    List<Workspace> result = service.handle(new ListWorkspacesForOrganizationQuery(organizationId));

    assertThat(result).containsExactly(workspace);
  }
}
