package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code workspace_roles} (ADR-0027). {@code permissions} is stored as its raw
 * JSON-serialized {@code text} column value — (de)serialization to/from {@code Set<String>} lives
 * in {@link JpaWorkspaceRoleRepository} (an injected {@code ObjectMapper}), same split
 * responsibility {@link WorkspaceMembershipEntity}'s sibling in {@code webhook-module} ({@code
 * WebhookEndpointEntity#subscribedEventTypes}) already establishes for an identical shape.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "workspace_roles")
public class WorkspaceRoleEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String name;

  @Column(name = "parent_role_id")
  private UUID parentRoleId;

  @Column(nullable = false)
  private String permissions;

  @Column(nullable = false)
  private boolean reserved;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected WorkspaceRoleEntity() {}

  @SuppressWarnings("java:S107") // one field per column — same rationale as every other
  // multi-column entity constructor in this codebase.
  public WorkspaceRoleEntity(
      final UUID id,
      final UUID organizationId,
      final String name,
      final UUID parentRoleId,
      final String permissions,
      final boolean reserved,
      final Instant createdAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.name = name;
    this.parentRoleId = parentRoleId;
    this.permissions = permissions;
    this.reserved = reserved;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public UUID getParentRoleId() {
    return parentRoleId;
  }

  public String getPermissions() {
    return permissions;
  }

  public boolean isReserved() {
    return reserved;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
