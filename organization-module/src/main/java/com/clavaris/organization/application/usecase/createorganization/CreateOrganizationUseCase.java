package com.clavaris.organization.application.usecase.createorganization;

/** Inbound port for {@code POST /api/v1/admin/organizations} (BR-ORG-06, operator-only). */
@FunctionalInterface
public interface CreateOrganizationUseCase {

  CreateOrganizationResult handle(CreateOrganizationCommand command);
}
