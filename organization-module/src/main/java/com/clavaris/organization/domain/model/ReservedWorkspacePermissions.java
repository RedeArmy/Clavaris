package com.clavaris.organization.domain.model;

import java.util.Set;

/**
 * ADR-0027 §2: the only permission strings Clavaris's own authorization logic ever branches on —
 * everything else in a {@link WorkspaceRole#permissions()} set is opaque, consumer-defined, and
 * never interpreted here. Structurally distinct namespace ({@code clavaris:} prefix) from any
 * consumer-defined permission, same "reserved vs. tenant-defined" split ADR-0010's own {@code
 * platform:} scope prefix already establishes for a different tier.
 */
public final class ReservedWorkspacePermissions {

  /** Can add/remove a Workspace's own members and change their assigned role. */
  public static final String MANAGE_MEMBERS = "clavaris:workspace:manage_members";

  /** Can create/update/delete a {@link WorkspaceRole} within the Workspace's own Organization. */
  public static final String MANAGE_ROLES = "clavaris:workspace:manage_roles";

  public static final Set<String> ALL = Set.of(MANAGE_MEMBERS, MANAGE_ROLES);

  private ReservedWorkspacePermissions() {
    // Constants only.
  }
}
