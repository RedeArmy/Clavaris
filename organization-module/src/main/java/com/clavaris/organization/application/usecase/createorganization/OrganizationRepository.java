package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.organization.domain.model.Organization;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOrganizationRepository}. Parked under {@code
 * createorganization} because that's this module's first use case, not because {@code existsById}
 * is scoped to it — {@code app}'s {@code OrganizationExistsCheckerBridge} (RegisterOAuthClient
 * slice) is the second consumer.
 */
public interface OrganizationRepository {

  void save(Organization organization);

  boolean existsById(UUID organizationId);
}
