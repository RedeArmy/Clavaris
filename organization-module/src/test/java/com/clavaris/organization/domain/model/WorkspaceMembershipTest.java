package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceMembershipTest {

  @Test
  void joinAssignsARandomIdAndCapturesTheGivenFields() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    WorkspaceMembership membership =
        WorkspaceMembership.join(workspaceId, accountId, WorkspaceRole.MEMBER);

    assertThat(membership.id()).isNotNull();
    assertThat(membership.workspaceId()).isEqualTo(workspaceId);
    assertThat(membership.accountId()).isEqualTo(accountId);
    assertThat(membership.role()).isEqualTo(WorkspaceRole.MEMBER);
    assertThat(membership.createdAt()).isNotNull();
  }

  @Test
  void withRoleReturnsACopyWithOnlyTheRoleChanged() {
    WorkspaceMembership original =
        WorkspaceMembership.join(UUID.randomUUID(), UUID.randomUUID(), WorkspaceRole.MEMBER);

    WorkspaceMembership promoted = original.withRole(WorkspaceRole.ADMIN);

    assertThat(promoted.id()).isEqualTo(original.id());
    assertThat(promoted.workspaceId()).isEqualTo(original.workspaceId());
    assertThat(promoted.accountId()).isEqualTo(original.accountId());
    assertThat(promoted.createdAt()).isEqualTo(original.createdAt());
    assertThat(promoted.role()).isEqualTo(WorkspaceRole.ADMIN);
    // The original instance is untouched — withRole never mutates in place.
    assertThat(original.role()).isEqualTo(WorkspaceRole.MEMBER);
  }
}
