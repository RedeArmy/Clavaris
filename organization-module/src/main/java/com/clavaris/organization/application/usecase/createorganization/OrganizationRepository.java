package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOrganizationRepository}. Parked under {@code
 * createorganization} because that's this module's first use case, not because {@code existsById}
 * is scoped to it — {@code app}'s {@code OrganizationExistsCheckerBridge} (RegisterOAuthClient
 * slice) is the second consumer. {@code findAllOwnedBy} (ADR-0012) is the dashboard's own
 * list-your- organizations query.
 */
public interface OrganizationRepository {

  void save(Organization organization);

  boolean existsById(UUID organizationId);

  List<Organization> findAllOwnedBy(
      @SuppressWarnings("PMD.LongVariable") UUID ownerPlatformAccountId);
}
