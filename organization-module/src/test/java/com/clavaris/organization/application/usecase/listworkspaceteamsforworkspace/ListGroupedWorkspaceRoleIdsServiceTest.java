package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListGroupedWorkspaceRoleIdsServiceTest {

  @Test
  void returnsEveryGroupedRoleIdTheRepositoryHasForThatWorkspace() {
    UUID workspaceId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    WorkspaceTeamRepository teams = mock(WorkspaceTeamRepository.class);
    when(teams.findAllGroupedRoleIdsForWorkspace(workspaceId)).thenReturn(Set.of(roleId));
    ListGroupedWorkspaceRoleIdsService service = new ListGroupedWorkspaceRoleIdsService(teams);

    Set<UUID> found = service.handle(new ListGroupedWorkspaceRoleIdsQuery(workspaceId));

    assertThat(found).containsExactly(roleId);
  }
}
