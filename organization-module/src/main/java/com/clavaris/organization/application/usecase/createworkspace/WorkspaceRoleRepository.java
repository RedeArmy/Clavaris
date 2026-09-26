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

  /**
   * Same write as {@link #save}, but flushed immediately rather than deferred to the enclosing
   * transaction's own commit — {@code CreateWorkspaceService}'s own reserved-role bootstrap is the
   * one call site that needs the {@code ux_workspace_roles_organization_id_name} constraint
   * violation (two concurrent first-Workspace creations for the same brand-new Organization) to
   * surface synchronously, catchable right where it's thrown, not at a commit boundary the calling
   * method has already returned past — same "a dedicated flushed write for the one race-sensitive
   * caller, plain {@link #save} everywhere else" precedent {@code KnownDeviceRepository#insert}
   * already establishes for an identical shape of race.
   */
  void saveAndFlush(WorkspaceRole role);

  Optional<WorkspaceRole> findById(UUID roleId);

  /**
   * The full, unbounded set of roles defined for one Organization — deliberately not paginated:
   * every consumer ({@code ManageMembersGuard}, {@link
   * com.clavaris.organization.domain.service.WorkspaceRoleHierarchy}, the member-add/role-change
   * forms) needs the whole graph to walk parent chains or resolve effective permissions, not a page
   * of it.
   */
  List<WorkspaceRole> findAllByOrganizationId(UUID organizationId);

  /**
   * {@code DeleteWorkspaceRoleService}'s own final step — only ever called after that use case has
   * already confirmed the role is unreserved and unreferenced by any membership (ADR-0027 §5).
   */
  void deleteById(UUID roleId);
}
