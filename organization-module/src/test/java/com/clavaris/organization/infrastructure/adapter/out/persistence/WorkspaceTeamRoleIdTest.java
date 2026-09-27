package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

// SDE-III review, 2026-09-27: found via a coverage pass (0% branch coverage on equals/hashCode) —
// no other test in this module exercised this @IdClass composite key's own identity logic at all.
// A wrong equals/hashCode here is exactly the class of bug that stays invisible until Hibernate's
// own first-level-cache/collection semantics for WorkspaceTeamRoleEntity silently misbehave, not
// something a repository integration test would necessarily surface.
class WorkspaceTeamRoleIdTest {

  @Test
  void isEqualToItself() {
    WorkspaceTeamRoleId id = new WorkspaceTeamRoleId(UUID.randomUUID(), UUID.randomUUID());

    assertThat(id).isEqualTo(id).hasSameHashCodeAs(id);
  }

  @Test
  void isEqualToAnotherInstanceWithTheSameTeamAndRoleId() {
    UUID teamId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();

    WorkspaceTeamRoleId first = new WorkspaceTeamRoleId(teamId, roleId);
    WorkspaceTeamRoleId second = new WorkspaceTeamRoleId(teamId, roleId);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void isNotEqualWhenTheWorkspaceTeamIdDiffers() {
    UUID roleId = UUID.randomUUID();

    WorkspaceTeamRoleId first = new WorkspaceTeamRoleId(UUID.randomUUID(), roleId);
    WorkspaceTeamRoleId second = new WorkspaceTeamRoleId(UUID.randomUUID(), roleId);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualWhenTheWorkspaceRoleIdDiffers() {
    UUID teamId = UUID.randomUUID();

    WorkspaceTeamRoleId first = new WorkspaceTeamRoleId(teamId, UUID.randomUUID());
    WorkspaceTeamRoleId second = new WorkspaceTeamRoleId(teamId, UUID.randomUUID());

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualToNullOrADifferentType() {
    WorkspaceTeamRoleId id = new WorkspaceTeamRoleId(UUID.randomUUID(), UUID.randomUUID());

    assertThat(id).isNotEqualTo(null).isNotEqualTo("not a WorkspaceTeamRoleId");
  }
}
