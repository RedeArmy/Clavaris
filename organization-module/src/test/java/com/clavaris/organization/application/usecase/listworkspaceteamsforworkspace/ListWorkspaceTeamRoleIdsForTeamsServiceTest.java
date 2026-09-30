package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWorkspaceTeamRoleIdsForTeamsServiceTest {

  @Test
  void returnsEveryTeamsOwnRoleIdsFromOneRepositoryCall() {
    UUID firstTeamId = UUID.randomUUID();
    UUID secondTeamId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    WorkspaceTeamRepository teams = mock(WorkspaceTeamRepository.class);
    when(teams.findRoleIdsByTeamIds(Set.of(firstTeamId, secondTeamId)))
        .thenReturn(Map.of(firstTeamId, List.of(roleId), secondTeamId, List.of()));
    ListWorkspaceTeamRoleIdsForTeamsService service =
        new ListWorkspaceTeamRoleIdsForTeamsService(teams);

    Map<UUID, List<UUID>> found =
        service.handle(
            new ListWorkspaceTeamRoleIdsForTeamsQuery(Set.of(firstTeamId, secondTeamId)));

    assertThat(found)
        .containsEntry(firstTeamId, List.of(roleId))
        .containsEntry(secondTeamId, List.of());
  }
}
