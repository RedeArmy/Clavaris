package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * ADR-0029: read-only projection of organization-module's own {@code workspace_roles} table — see
 * {@link WorkspaceMembershipReadEntity}'s own Javadoc for the full data-contract reasoning. Only
 * {@code name} is mapped; the Users tab's own Role column has no need for permissions/hierarchy.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "workspace_roles")
public class WorkspaceRoleReadEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  protected WorkspaceRoleReadEntity() {}

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
