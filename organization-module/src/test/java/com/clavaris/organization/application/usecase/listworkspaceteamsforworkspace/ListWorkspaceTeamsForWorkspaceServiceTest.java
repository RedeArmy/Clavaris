package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWorkspaceTeamsForWorkspaceServiceTest {

  @Test
  void returnsEveryTeamTheRepositoryHasForThatWorkspace() {
    UUID workspaceId = UUID.randomUUID();
    WorkspaceTeam team = WorkspaceTeam.define(workspaceId, "QA");
    WorkspaceTeamRepository teams = mock(WorkspaceTeamRepository.class);
    when(teams.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(team));
    ListWorkspaceTeamsForWorkspaceService service =
        new ListWorkspaceTeamsForWorkspaceService(teams);

    List<WorkspaceTeam> found =
        service.handle(new ListWorkspaceTeamsForWorkspaceQuery(workspaceId));

    assertThat(found).containsExactly(team);
  }
}
