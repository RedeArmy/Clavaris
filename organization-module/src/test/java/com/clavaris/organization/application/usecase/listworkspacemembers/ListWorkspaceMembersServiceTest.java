package com.clavaris.organization.application.usecase.listworkspacemembers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListWorkspaceMembersServiceTest {

  @Test
  void returnsEveryMembershipTheRepositoryReturns() {
    WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    UUID workspaceId = UUID.randomUUID();
    WorkspaceMembership membership =
        WorkspaceMembership.join(workspaceId, UUID.randomUUID(), WorkspaceRole.ADMIN);
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(membership));
    ListWorkspaceMembersService service = new ListWorkspaceMembersService(memberships);

    List<WorkspaceMembership> result = service.handle(new ListWorkspaceMembersQuery(workspaceId));

    assertThat(result).containsExactly(membership);
  }
}
