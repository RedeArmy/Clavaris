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

  /**
   * BR-DATA-02/03's own organization-level equivalent: a real, permanent hard delete. Only cascades
   * at the database level to this module's own {@code rate_limit_policies} (migration {@code
   * V20260826110000}, same-module) — every other table this Organization owns (identity-module's
   * {@code accounts}/{@code signing_keys}, client-registry-module's {@code oauth_clients}) is
   * erased explicitly, at the application layer, before this method is called. See {@code
   * DeleteOrganizationService}'s own Javadoc for the full reasoning.
   */
  void deleteById(UUID organizationId);
}
