package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import java.util.Set;
import java.util.UUID;

/**
 * ADR-0028: every roleId grouped into any of this Workspace's own teams — the dashboard's own Teams
 * section subtracts this from the Organization's full role list to render the "ungrouped roles"
 * section.
 */
@FunctionalInterface
public interface ListGroupedWorkspaceRoleIdsUseCase {

  Set<UUID> handle(ListGroupedWorkspaceRoleIdsQuery query);
}
