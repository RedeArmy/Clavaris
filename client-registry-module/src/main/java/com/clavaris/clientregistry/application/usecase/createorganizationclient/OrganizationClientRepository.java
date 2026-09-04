package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import com.clavaris.clientregistry.domain.model.OrganizationClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOrganizationClientRepository}. Also the lookup path the
 * platform issuer's {@code RegisteredClientRepository} adapter (app module) uses at token-request
 * time for an {@code OrganizationClient} (ADR-0023) — same role {@code PlatformClientRepository}'s
 * own identical Javadoc already describes for {@code PlatformClient}.
 */
@SuppressWarnings("PMD.LongVariable")
public interface OrganizationClientRepository {

  Optional<OrganizationClient> findByClientId(String clientId);

  // TD-SEC-010's own precedent: JdbcOAuth2AuthorizationService reconstructs a RegisteredClient by
  // its own internal id, not clientId, on every reload of a persisted OAuth2Authorization row.
  @SuppressWarnings("PMD.ShortVariable")
  Optional<OrganizationClient> findById(UUID id);

  List<OrganizationClient> findAllByOrganizationId(UUID organizationId);

  void save(OrganizationClient organizationClient);

  // BR-DATA-02/03's own client-registry-module equivalent — same role OAuthClientRepository's own
  // identical method plays for DeleteOrganizationService (via OrganizationOAuthClientsEraser).
  void deleteAllByOrganizationId(UUID organizationId);
}
