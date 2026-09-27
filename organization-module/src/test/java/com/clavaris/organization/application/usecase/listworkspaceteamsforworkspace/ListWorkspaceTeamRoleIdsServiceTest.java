package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWorkspaceTeamRoleIdsServiceTest {

  @Test
  void returnsEveryRoleIdTheRepositoryHasForThatTeam() {
    UUID teamId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    WorkspaceTeamRepository teams = mock(WorkspaceTeamRepository.class);
    when(teams.findRoleIdsByTeamId(teamId)).thenReturn(List.of(roleId));
    ListWorkspaceTeamRoleIdsService service = new ListWorkspaceTeamRoleIdsService(teams);

    List<UUID> found = service.handle(new ListWorkspaceTeamRoleIdsQuery(teamId));

    assertThat(found).containsExactly(roleId);
  }
}
