package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.OAuthClient} (framework-free) and
 * {@link OAuthClientEntity}.
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
            client.createdAt()));
  }

  @Override
  public Optional<OAuthClient> findByClientId(final String clientId) {
    return oauthClients.findByClientId(clientId).map(this::toDomain);
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
        entity.getCreatedAt());
  }

  private List<String> readJsonArray(final String json) {
    return Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
