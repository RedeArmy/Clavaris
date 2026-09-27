package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code workspace_teams} (ADR-0028). */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "workspace_teams")
public class WorkspaceTeamEntity {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WorkspaceTeamEntity() {}

  public WorkspaceTeamEntity(
      final UUID id, final UUID workspaceId, final String name, final Instant createdAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.name = name;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
