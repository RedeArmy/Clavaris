package com.clavaris.organization.application.usecase.deleteorganization;

/**
 * BR-DATA-02/03's own organization-level equivalent — hard-deletes an {@code Organization} and its
 * entire owned account pool.
 */
@FunctionalInterface
public interface DeleteOrganizationUseCase {

  void handle(DeleteOrganizationCommand command);
}
