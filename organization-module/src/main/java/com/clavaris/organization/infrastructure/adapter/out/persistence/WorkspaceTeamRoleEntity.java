package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * JPA row mapping for {@code workspace_team_roles} (ADR-0028 §2) — a pure join row, no relationship
 * navigation mapped on purpose: every port method that needs to cross from a Workspace to its own
 * teams' role associations does so in two plain queries in {@code JpaWorkspaceTeamRepository}
 * itself (first the team ids for a Workspace, then this table filtered by those ids), not a JPA
 * {@code @ManyToOne} graph.
 */
@Entity
@Table(name = "workspace_team_roles")
@IdClass(WorkspaceTeamRoleId.class)
public class WorkspaceTeamRoleEntity {

  @Id
  @Column(name = "workspace_team_id")
  private UUID workspaceTeamId;

  @Id
  @Column(name = "workspace_role_id")
  private UUID workspaceRoleId;

  protected WorkspaceTeamRoleEntity() {}

  public WorkspaceTeamRoleEntity(final UUID workspaceTeamId, final UUID workspaceRoleId) {
    this.workspaceTeamId = workspaceTeamId;
    this.workspaceRoleId = workspaceRoleId;
  }

  public UUID getWorkspaceTeamId() {
    return workspaceTeamId;
  }

  public UUID getWorkspaceRoleId() {
    return workspaceRoleId;
  }
}
