package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.OrganizationClient}
 * (framework-free) and {@link OrganizationClientEntity} — same shape as {@code
 * JpaPlatformClientRepository}.
 */
@SuppressWarnings("PMD.LongVariable")
@Repository
class JpaOrganizationClientRepository implements OrganizationClientRepository {

  private final SpringDataOrganizationClientJpaRepository organizationClients;
  private final ObjectMapper objectMapper;

  /* package */ JpaOrganizationClientRepository(
      final SpringDataOrganizationClientJpaRepository organizationClients,
      final ObjectMapper objectMapper) {
    this.organizationClients = organizationClients;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<OrganizationClient> findByClientId(final String clientId) {
    return organizationClients.findByClientId(clientId).map(this::toDomain);
  }

  @SuppressWarnings("PMD.ShortVariable")
  @Override
  public Optional<OrganizationClient> findById(final UUID id) {
    return organizationClients.findById(id).map(this::toDomain);
  }

  @Override
  public List<OrganizationClient> findAllByOrganizationId(final UUID organizationId) {
    return organizationClients.findAllByOrganizationId(organizationId).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void save(final OrganizationClient organizationClient) {
    organizationClients.save(
        new OrganizationClientEntity(
            organizationClient.id(),
            organizationClient.organizationId(),
            organizationClient.clientId(),
            organizationClient.clientSecretHash(),
            objectMapper.writeValueAsString(organizationClient.allowedScopes()),
            organizationClient.createdAt(),
            organizationClient.active()));
  }

  @Override
  public void deleteAllByOrganizationId(final UUID organizationId) {
    organizationClients.deleteAllByOrganizationId(organizationId);
  }

  private OrganizationClient toDomain(final OrganizationClientEntity entity) {
    final List<String> scopes =
        Arrays.asList(objectMapper.readValue(entity.getAllowedScopes(), String[].class));
    return OrganizationClient.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getClientId(),
        entity.getClientSecretHash(),
        scopes,
        entity.getCreatedAt(),
        entity.isActive());
  }
}
