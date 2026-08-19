package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.organization.domain.model.Organization;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOrganizationRepository}.
 */
@FunctionalInterface
public interface OrganizationRepository {

  void save(Organization organization);
}
