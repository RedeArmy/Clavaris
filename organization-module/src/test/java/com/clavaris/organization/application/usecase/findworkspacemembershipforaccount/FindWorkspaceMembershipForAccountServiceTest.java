package com.clavaris.organization.application.usecase.findworkspacemembershipforaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindWorkspaceMembershipForAccountServiceTest {

  @Test
  void returnsTheAccountsOwnMembershipWhenOneExists() {
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        WorkspaceMembership.join(UUID.randomUUID(), accountId, UUID.randomUUID());
    WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    when(memberships.findAllByAccountId(accountId)).thenReturn(List.of(membership));
    FindWorkspaceMembershipForAccountService service =
        new FindWorkspaceMembershipForAccountService(memberships);

    Optional<WorkspaceMembership> found =
        service.handle(new FindWorkspaceMembershipForAccountQuery(accountId));

    assertThat(found).contains(membership);
  }

  @Test
  void returnsEmptyWhenTheAccountBelongsToNoWorkspace() {
    UUID accountId = UUID.randomUUID();
    WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    when(memberships.findAllByAccountId(accountId)).thenReturn(List.of());
    FindWorkspaceMembershipForAccountService service =
        new FindWorkspaceMembershipForAccountService(memberships);

    Optional<WorkspaceMembership> found =
        service.handle(new FindWorkspaceMembershipForAccountQuery(accountId));

    assertThat(found).isEmpty();
  }

  @Test
  void takesTheFirstMembershipWhenMoreThanOneExists() {
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership first =
        WorkspaceMembership.join(UUID.randomUUID(), accountId, UUID.randomUUID());
    WorkspaceMembership second =
        WorkspaceMembership.join(UUID.randomUUID(), accountId, UUID.randomUUID());
    WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    when(memberships.findAllByAccountId(accountId)).thenReturn(List.of(first, second));
    FindWorkspaceMembershipForAccountService service =
        new FindWorkspaceMembershipForAccountService(memberships);

    Optional<WorkspaceMembership> found =
        service.handle(new FindWorkspaceMembershipForAccountQuery(accountId));

    assertThat(found).contains(first);
  }
}
