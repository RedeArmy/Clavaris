package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** JPA composite-key class for {@link WorkspaceTeamRoleEntity} ({@code @IdClass}). */
public final class WorkspaceTeamRoleId implements Serializable {

  private static final long serialVersionUID = 1L;

  private UUID workspaceTeamId;
  private UUID workspaceRoleId;

  private WorkspaceTeamRoleId() {
    // JPA needs a no-arg constructor to build an instance via reflection when reading a row back —
    // private is fine, Hibernate invokes it via setAccessible, same reason WorkspaceTeamEntity's
    // own protected no-arg constructor isn't flagged (that class isn't final, so protected is the
    // right visibility there; this one is, so PMD correctly wants private instead).
  }

  public WorkspaceTeamRoleId(final UUID workspaceTeamId, final UUID workspaceRoleId) {
    this.workspaceTeamId = workspaceTeamId;
    this.workspaceRoleId = workspaceRoleId;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final WorkspaceTeamRoleId that)) {
      return false;
    }
    return Objects.equals(workspaceTeamId, that.workspaceTeamId)
        && Objects.equals(workspaceRoleId, that.workspaceRoleId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workspaceTeamId, workspaceRoleId);
  }
}
