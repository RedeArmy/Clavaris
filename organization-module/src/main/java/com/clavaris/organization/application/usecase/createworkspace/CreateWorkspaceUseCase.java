package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.organization.domain.model.Workspace;

/** Inbound port for {@code POST /api/v1/admin/organizations/{organizationId}/workspaces}. */
@FunctionalInterface
public interface CreateWorkspaceUseCase {

  /**
   * @throws OrganizationNotFoundException if {@code command.organizationId()} doesn't exist
   */
  Workspace handle(CreateWorkspaceCommand command);
}
