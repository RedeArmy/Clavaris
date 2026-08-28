package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.WorkspaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code workspace_memberships} — {@code accountId} is a plain column, no
 * {@code @ManyToOne}/FK: see the owning migration's own comment for why (no cross-module JPA
 * relationship is possible here). {@code role} stored as its enum name ({@link EnumType#STRING}) —
 * a future reordering of {@link WorkspaceRole}'s constants must never silently change a persisted
 * row's meaning, which {@link EnumType#ORDINAL} would risk.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "workspace_memberships")
public class WorkspaceMembershipEntity {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private WorkspaceRole role;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WorkspaceMembershipEntity() {}

  public WorkspaceMembershipEntity(
      final UUID id,
      final UUID workspaceId,
      final UUID accountId,
      final WorkspaceRole role,
      final Instant createdAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.accountId = accountId;
    this.role = role;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public WorkspaceRole getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
