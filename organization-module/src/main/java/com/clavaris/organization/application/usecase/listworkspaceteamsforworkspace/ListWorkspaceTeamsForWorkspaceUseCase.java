package com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace;

import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;

/**
 * ADR-0028: the Workspace-detail dashboard's own Teams section needs this Workspace's full team
 * list to render one card per team.
 */
@FunctionalInterface
public interface ListWorkspaceTeamsForWorkspaceUseCase {

  List<WorkspaceTeam> handle(ListWorkspaceTeamsForWorkspaceQuery query);
}
