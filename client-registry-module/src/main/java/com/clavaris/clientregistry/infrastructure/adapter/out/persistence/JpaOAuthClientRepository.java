package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.OAuthClient} (framework-free) and
 * {@link OAuthClientEntity}.
 *
 * <p>TD-PERF-019: {@code save} calls {@link EntityManager#persist} directly, not {@code
 * SpringDataOAuthClientJpaRepository#save} — {@code OAuthClientRepository}'s own interface has no
 * update-shaped method at all ({@code RegisterOAuthClientService} is the only caller of this
 * method, confirmed by reading every caller of this port; branding/redirect-policy/domain-config
 * updates all go through their own separate tables/repositories, never back through this one), so
 * {@code save} is genuinely insert-only and {@code merge()}'s pre-existence {@code SELECT} was pure
 * waste here. {@code @Transactional} on {@code save} itself — same "a raw {@code persist} call
 * needs one already open on the current thread, unlike {@code SimpleJpaRepository#save}'s own
 * built-in one" reasoning {@code JpaWorkspaceRepository#save}'s own identical annotation documents.
 */
@Repository
class JpaOAuthClientRepository implements OAuthClientRepository {

  private final SpringDataOAuthClientJpaRepository oauthClients;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;

  /* package */ JpaOAuthClientRepository(
      final SpringDataOAuthClientJpaRepository oauthClients,
      final ObjectMapper objectMapper,
      final EntityManager entityManager) {
    this.oauthClients = oauthClients;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public void save(final OAuthClient client) {
    entityManager.persist(
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
            client.createdAt()));
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
        entity.getCreatedAt());
  }

  private List<String> readJsonArray(final String json) {
    return Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
