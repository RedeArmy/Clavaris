package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code workspace_memberships} — {@code accountId} is a plain column, no
 * {@code @ManyToOne}/FK: see the owning migration's own comment for why (no cross-module JPA
 * relationship is possible here). {@code roleId} (ADR-0027 — replaces the old fixed-enum {@code
 * role}) is likewise a plain, nullable column, not a {@code @ManyToOne} to {@code
 * WorkspaceRoleEntity}: {@code null} means "no role currently assigned," an explicitly allowed
 * state (ADR-0027 §5).
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

  @Column(name = "role_id")
  private UUID roleId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WorkspaceMembershipEntity() {}

  public WorkspaceMembershipEntity(
      final UUID id,
      final UUID workspaceId,
      final UUID accountId,
      final UUID roleId,
      final Instant createdAt) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.accountId = accountId;
    this.roleId = roleId;
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

  public UUID getRoleId() {
    return roleId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
