package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.PlatformClient} (framework-free)
 * and {@link PlatformClientEntity}.
 */
@Repository
class JpaPlatformClientRepository implements PlatformClientRepository {

  private final SpringDataPlatformClientJpaRepository platformClients;
  private final ObjectMapper objectMapper;

  // Constructed only by Spring's own component scan (via @Repository above) —
  // PlatformClientRepository
  // (the port) is the only type callers outside this package should depend on.
  /* package */ JpaPlatformClientRepository(
      final SpringDataPlatformClientJpaRepository platformClients,
      final ObjectMapper objectMapper) {
    this.platformClients = platformClients;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean existsByClientId(final String clientId) {
    return platformClients.existsByClientId(clientId);
  }

  @Override
  public Optional<PlatformClient> findByClientId(final String clientId) {
    return platformClients.findByClientId(clientId).map(this::toDomain);
  }

  @Override
  public void save(final PlatformClient platformClient) {
    platformClients.save(
        new PlatformClientEntity(
            platformClient.id(),
            platformClient.clientId(),
            platformClient.clientSecretHash(),
            objectMapper.writeValueAsString(platformClient.allowedScopes()),
            platformClient.createdAt()));
  }

  private PlatformClient toDomain(final PlatformClientEntity entity) {
    final List<String> scopes =
        Arrays.asList(objectMapper.readValue(entity.getAllowedScopes(), String[].class));
    return PlatformClient.reconstitute(
        entity.getId(),
        entity.getClientId(),
        entity.getClientSecretHash(),
        scopes,
        entity.getCreatedAt());
  }
}
