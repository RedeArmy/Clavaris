package com.clavaris.organization.application.usecase.listworkspacememberspaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.organization.domain.model.WorkspaceMembership;

/**
 * TD-PERF-020: the dashboard's own paginated sibling of {@code ListWorkspaceMembersUseCase} — that
 * use case stays unbounded, since the REST admin API's own {@code ListWorkspaceMembersController}
 * genuinely needs the full list; this one exists only for {@code PlatformWorkspaceController}'s own
 * Workspace-detail Members section.
 */
@FunctionalInterface
public interface ListWorkspaceMembersPagedUseCase {

  Page<WorkspaceMembership> handle(ListWorkspaceMembersPagedQuery query);
}
