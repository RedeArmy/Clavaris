package com.clavaris.organization.application.usecase.listworkspacemembers;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.List;

/** Inbound port for {@code GET /api/v1/admin/workspaces/{workspaceId}/members}. */
@FunctionalInterface
public interface ListWorkspaceMembersUseCase {

  List<WorkspaceMembership> handle(ListWorkspaceMembersQuery query);
}
