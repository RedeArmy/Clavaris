package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.common.infrastructure.adapter.out.persistence.SpringDataKeysetPageMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.OAuthClient} (framework-free) and
 * {@link OAuthClientEntity}.
 *
 * <p>SDE-III review, 2026-09-11: {@code save} now delegates to {@code
 * SpringDataOAuthClientJpaRepository#save} (an upsert), no longer a raw {@link
 * jakarta.persistence.EntityManager#persist} call — reversed from this method's own earlier
 * insert-only optimization (TD-PERF-019) now that {@code save} genuinely has two callers with
 * different needs: {@code RegisterOAuthClientService} (always a fresh row) and the new {@code
 * DeactivateOAuthClientService}/{@code RotateOAuthClientSecretService} (always an update to an
 * existing one, mirroring {@code JpaOrganizationClientRepository#save}'s own identical shape for
 * the sibling credential type that already supports both). The one extra pre-existence {@code
 * SELECT} {@code merge()} performs on every call is the real, accepted cost of that correctness —
 * same trade-off {@code JpaOrganizationClientRepository} already made.
 */
@Repository
class JpaOAuthClientRepository implements OAuthClientRepository {

  private final SpringDataOAuthClientJpaRepository oauthClients;
  private final ObjectMapper objectMapper;

  /* package */ JpaOAuthClientRepository(
      final SpringDataOAuthClientJpaRepository oauthClients, final ObjectMapper objectMapper) {
    this.oauthClients = oauthClients;
    this.objectMapper = objectMapper;
  }

  // SDE-III review, 2026-09-15: ConcurrentClientModificationException's own Javadoc has the full
  // rationale — client.version() is the version this OAuthClient was read at (unchanged through
  // deactivate()/rotateSecret()'s own immutable-mutation shape), so merge()'s own version check
  // against the real, current DB row is exactly the guard a racing deactivate+rotate-secret pair
  // needed. OptimisticLockingFailureException, Spring's own portable exception — never the
  // JPA/Hibernate-specific one — is the only type this catch ever needs to know about.
  //
  // saveAndFlush, not save: confirmed live (a real integration test initially failed on exactly
  // this) that Hibernate defers a merge()'s actual UPDATE to flush/commit time by default, well
  // after this method already returned — the conflict was thrown from the *caller's* transaction
  // commit, past this catch entirely, silently defeating it. Forcing the flush here makes the
  // version check run synchronously, inside this try, where the catch below can actually see it.
  @Override
  public void save(final OAuthClient client) {
    try {
      oauthClients.saveAndFlush(
          new OAuthClientEntity(
              client.id(),
              client.organizationId(),
              client.clientId(),
              client.clientSecretHash(),
              objectMapper.writeValueAsString(client.redirectUris()),
              objectMapper.writeValueAsString(client.allowedGrantTypes()),
              objectMapper.writeValueAsString(client.allowedScopes()),
              client.requireConsent(),
              objectMapper.writeValueAsString(client.postLogoutRedirectUris()),
              client.createdAt(),
              client.active(),
              client.version()));
    } catch (final OptimisticLockingFailureException _) {
      throw new ConcurrentClientModificationException(client.clientId());
    }
  }

  @Override
  public Optional<OAuthClient> findByClientId(final String clientId) {
    return oauthClients.findByClientId(clientId).map(this::toDomain);
  }

  @SuppressWarnings("PMD.ShortVariable") // matches the port's own parameter naming
  @Override
  public Optional<OAuthClient> findById(final UUID id) {
    return oauthClients.findById(id).map(this::toDomain);
  }

  @Override
  public List<OAuthClient> findAllByOrganizationId(final UUID organizationId) {
    return oauthClients.findAllByOrganizationId(organizationId).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void deleteAllByOrganizationId(final UUID organizationId) {
    oauthClients.deleteAllByOrganizationId(organizationId);
    oauthClients.flush();
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): newest-first, id as a tiebreaker — same reasoning
  // organization-module's own JpaOrganizationRepository#findKeysetPageOwnedBy already documents.
  @Override
  @SuppressWarnings("PMD.OnlyOneReturn") // three real, distinct exits — first/after/before.
  public KeysetPage<OAuthClient> findKeysetPageByOrganizationId(
      final UUID organizationId, final KeysetPageRequest pageRequest) {
    final org.springframework.data.domain.PageRequest limit =
        org.springframework.data.domain.PageRequest.of(0, pageRequest.size() + 1);
    if (pageRequest.after() != null) {
      final KeysetCursor cursor = pageRequest.after();
      return SpringDataKeysetPageMapper.forward(
          oauthClients.findPageByOrganizationIdAfter(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          true,
          this::toDomain,
          this::cursorOf);
    }
    if (pageRequest.before() != null) {
      final KeysetCursor cursor = pageRequest.before();
      return SpringDataKeysetPageMapper.backward(
          oauthClients.findPageByOrganizationIdBefore(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          this::toDomain,
          this::cursorOf);
    }
    return SpringDataKeysetPageMapper.forward(
        oauthClients.findFirstPageByOrganizationId(organizationId, limit),
        pageRequest.size(),
        false,
        this::toDomain,
        this::cursorOf);
  }

  private KeysetCursor cursorOf(final OAuthClientEntity entity) {
    return new KeysetCursor(entity.getCreatedAt(), entity.getId());
  }

  private OAuthClient toDomain(final OAuthClientEntity entity) {
    return OAuthClient.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getClientId(),
        entity.getClientSecretHash(),
        readJsonArray(entity.getRedirectUris()),
        readJsonArray(entity.getAllowedGrantTypes()),
        readJsonArray(entity.getAllowedScopes()),
        entity.isRequireConsent(),
        readJsonArray(entity.getPostLogoutRedirectUris()),
        entity.getCreatedAt(),
        entity.isActive(),
        entity.getVersion());
  }

  private List<String> readJsonArray(final String json) {
    return Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
