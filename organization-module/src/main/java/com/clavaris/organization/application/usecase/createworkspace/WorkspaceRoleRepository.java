package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWorkspaceRoleRepository}. ADR-0027: {@link
 * WorkspaceRole} is Organization-scoped, not Workspace-scoped (one role definition set is shared
 * across every Workspace an Organization owns). Parked under {@code createworkspace}, same "first
 * consumer owns the port" precedent {@link WorkspaceRepository}'s own Javadoc documents — {@code
 * addworkspacemember}, {@code changeworkspacememberrole}, and {@code removeworkspacemember} (via
 * {@code ManageMembersGuard}) are the other consumers.
 */
public interface WorkspaceRoleRepository {

  void save(WorkspaceRole role);

  Optional<WorkspaceRole> findById(UUID roleId);

  /**
   * The full, unbounded set of roles defined for one Organization — deliberately not paginated:
   * every consumer ({@code ManageMembersGuard}, {@link
   * com.clavaris.organization.domain.service.WorkspaceRoleHierarchy}, the member-add/role-change
   * forms) needs the whole graph to walk parent chains or resolve effective permissions, not a page
   * of it.
   */
  List<WorkspaceRole> findAllByOrganizationId(UUID organizationId);
}
