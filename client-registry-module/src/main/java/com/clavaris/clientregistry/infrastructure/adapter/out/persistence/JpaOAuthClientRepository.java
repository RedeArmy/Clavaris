package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

  @Override
  public void save(final OAuthClient client) {
    oauthClients.save(
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
            client.active()));
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
        entity.isActive());
  }

  private List<String> readJsonArray(final String json) {
    return Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
