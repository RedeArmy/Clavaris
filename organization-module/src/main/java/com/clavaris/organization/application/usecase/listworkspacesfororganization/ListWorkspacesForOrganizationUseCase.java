package com.clavaris.organization.application.usecase.listworkspacesfororganization;

import com.clavaris.organization.domain.model.Workspace;
import java.util.List;

/** Inbound port for {@code GET /api/v1/admin/organizations/{organizationId}/workspaces}. */
@FunctionalInterface
public interface ListWorkspacesForOrganizationUseCase {

  List<Workspace> handle(ListWorkspacesForOrganizationQuery query);
}
