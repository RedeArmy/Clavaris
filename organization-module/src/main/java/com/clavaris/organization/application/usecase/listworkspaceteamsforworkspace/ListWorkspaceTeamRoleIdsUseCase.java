package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import java.util.List;
import java.util.UUID;

/**
 * ADR-0028: the Workspace-detail dashboard's own Teams section needs each team's own grouped
 * roleIds to render underneath it.
 */
@FunctionalInterface
public interface ListWorkspaceTeamRoleIdsUseCase {

  List<UUID> handle(ListWorkspaceTeamRoleIdsQuery query);
}
