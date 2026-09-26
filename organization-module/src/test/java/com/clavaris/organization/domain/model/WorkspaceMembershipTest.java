package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceMembershipTest {

  @Test
  void joinAssignsARandomIdAndCapturesTheGivenFields() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();

    WorkspaceMembership membership = WorkspaceMembership.join(workspaceId, accountId, roleId);

    assertThat(membership.id()).isNotNull();
    assertThat(membership.workspaceId()).isEqualTo(workspaceId);
    assertThat(membership.accountId()).isEqualTo(accountId);
    assertThat(membership.roleId()).isEqualTo(roleId);
    assertThat(membership.createdAt()).isNotNull();
  }

  @Test
  void joinAllowsANullRoleId() {
    WorkspaceMembership membership =
        WorkspaceMembership.join(UUID.randomUUID(), UUID.randomUUID(), null);

    assertThat(membership.roleId()).isNull();
  }

  @Test
  void withRoleIdReturnsACopyWithOnlyTheRoleIdChanged() {
    UUID originalRoleId = UUID.randomUUID();
    UUID newRoleId = UUID.randomUUID();
    WorkspaceMembership original =
        WorkspaceMembership.join(UUID.randomUUID(), UUID.randomUUID(), originalRoleId);

    WorkspaceMembership reassigned = original.withRoleId(newRoleId);

    assertThat(reassigned.id()).isEqualTo(original.id());
    assertThat(reassigned.workspaceId()).isEqualTo(original.workspaceId());
    assertThat(reassigned.accountId()).isEqualTo(original.accountId());
    assertThat(reassigned.createdAt()).isEqualTo(original.createdAt());
    assertThat(reassigned.roleId()).isEqualTo(newRoleId);
    // The original instance is untouched — withRoleId never mutates in place.
    assertThat(original.roleId()).isEqualTo(originalRoleId);
  }

  @Test
  void withRoleIdCanUnassignByPassingNull() {
    WorkspaceMembership original =
        WorkspaceMembership.join(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    WorkspaceMembership unassigned = original.withRoleId(null);

    assertThat(unassigned.roleId()).isNull();
  }
}
