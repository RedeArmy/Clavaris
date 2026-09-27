package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * ADR-0029: read-only projection of organization-module's own {@code workspace_memberships} table —
 * a data contract, same precedent {@code webhook-module}'s own outbox-reading entities already
 * establish (own read-side JPA entity against another module's table, never that module's Java
 * type, never a migration owned here). Only the columns this reader actually needs are mapped; this
 * class never writes, so the table's other columns (e.g. {@code created_at}) are irrelevant to it.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "workspace_memberships")
public class WorkspaceMembershipReadEntity {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "role_id")
  private UUID roleId;

  protected WorkspaceMembershipReadEntity() {}

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
}
