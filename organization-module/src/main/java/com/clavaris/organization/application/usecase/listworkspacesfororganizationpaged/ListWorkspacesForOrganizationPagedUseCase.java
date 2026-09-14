package com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.organization.domain.model.Workspace;

/**
 * TD-PERF-020: the dashboard's own paginated sibling of {@code
 * ListWorkspacesForOrganizationUseCase} — that use case stays unbounded, since {@code
 * GetAuditLogForOrganizationService} and the REST admin API's own {@code ListWorkspacesController}
 * both genuinely need the full list; this one exists only for {@code
 * PlatformOrganizationDetailController}'s own Workspaces section.
 */
@FunctionalInterface
public interface ListWorkspacesForOrganizationPagedUseCase {

  Page<Workspace> handle(ListWorkspacesForOrganizationPagedQuery query);
}
