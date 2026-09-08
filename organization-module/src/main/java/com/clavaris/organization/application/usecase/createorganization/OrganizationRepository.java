package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.Optional;
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

  /**
   * TD-PERF-019: same write as {@link #save}, for the call sites that know for a fact this {@code
   * Organization} has never been persisted before — {@code CreateOrganizationService} and {@code
   * CreateProductionEnvironmentService}'s own new {@code PRODUCTION} row (constructed via {@code
   * Organization.register}/{@code Organization.registerProductionEnvironment}). That same service's
   * other write — the source {@code DEVELOPMENT} Organization's updated {@code
   * linkedEnvironmentOrganizationId}, loaded via {@link #findById} — is a genuine update and must
   * keep calling {@link #save}, same for {@code SetSocialLoginPolicyForOrganizationService}. Same
   * rationale {@code AccountRepository#insert}'s own identical addition documents.
   */
  void insert(Organization organization);

  boolean existsById(UUID organizationId);

  /**
   * TD-ARCH-007: {@code DeleteOrganizationService}'s own read — needs the full {@code Organization}
   * (specifically its {@code name}) to build {@code OrganizationDeletedEvent}, the same reason
   * identity-module's own {@code DeleteAccountService} reads the full {@code Account} rather than
   * just checking {@code existsById}.
   */
  Optional<Organization> findById(UUID organizationId);

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
