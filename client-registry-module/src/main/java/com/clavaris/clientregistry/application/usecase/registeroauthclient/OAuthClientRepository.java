package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOAuthClientRepository}. {@code findByClientId} is also
 * the lookup path the per-Organization issuer's {@code RegisteredClientRepository} adapter (app
 * module) uses at token/authorize-request time.
 */
public interface OAuthClientRepository {

  void save(OAuthClient client);

  Optional<OAuthClient> findByClientId(String clientId);

  // TD-SEC-010 (closed): JdbcOAuth2AuthorizationService (TD-SEC-003) reconstructs a
  // RegisteredClient
  // by its own internal id, not clientId, whenever it reloads a persisted OAuth2Authorization row —
  // OrganizationRegisteredClientRepository.findById needs this to stop being an unconditional
  // UnsupportedOperationException the moment authorization state actually persists.
  // "id", not "clientId" — this looks up the entity's own primary key, matching
  // RegisteredClientRepository.findById's own parameter naming (the SPI this ultimately serves).
  @SuppressWarnings("PMD.ShortVariable")
  Optional<OAuthClient> findById(UUID id);

  /**
   * SDE-III review, 2026-09-11: genuinely missing until now — added alongside the dashboard's own
   * real {@code OAuthClient} listing page. {@code OrganizationClientRepository} (the Secret Key
   * one) already had this exact method, which is what caused the original naming mix-up documented
   * in {@code technical-debt-register.md} TD-FUT-032: this method's absence is the real, concrete
   * signal that "OAuth client management" hadn't actually shipped yet.
   */
  List<OAuthClient> findAllByOrganizationId(UUID organizationId);

  /**
   * TD-PERF-020: the dashboard's own paginated sibling of {@link #findAllByOrganizationId} — used
   * only by {@code ListOAuthClientsPagedService}'s own display query. {@link
   * #findAllByOrganizationId} itself stays untouched — {@code PlatformOAuthClientController} still
   * resolves a {@code clientId} path variable against the FULL, unbounded list before any mutation
   * (deactivate/rotate), a real anti-enumeration ownership check that must never miss a client
   * sitting on a page the dashboard isn't currently displaying.
   */
  Page<OAuthClient> findPageByOrganizationId(UUID organizationId, PageRequest pageRequest);

  /**
   * BR-DATA-02/03's own organization-level equivalent — every {@code OAuthClient} this Organization
   * ever registered.
   */
  void deleteAllByOrganizationId(UUID organizationId);
}
