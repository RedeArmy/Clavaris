package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.ConcurrentClientModificationException;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
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

  // SDE-III review, 2026-09-15: same conflict translation, and same saveAndFlush-not-save
  // reasoning, as JpaOAuthClientRepository's own identical catch — see
  // ConcurrentClientModificationException's own Javadoc for the rationale.
  @Override
  public void save(final OrganizationClient organizationClient) {
    try {
      organizationClients.saveAndFlush(
          new OrganizationClientEntity(
              organizationClient.id(),
              organizationClient.organizationId(),
              organizationClient.clientId(),
              organizationClient.clientSecretHash(),
              objectMapper.writeValueAsString(organizationClient.allowedScopes()),
              organizationClient.createdAt(),
              organizationClient.active(),
              organizationClient.version()));
    } catch (final OptimisticLockingFailureException _) {
      throw new ConcurrentClientModificationException(organizationClient.clientId());
    }
  }

  @Override
  public void deleteAllByOrganizationId(final UUID organizationId) {
    organizationClients.deleteAllByOrganizationId(organizationId);
  }

  // TD-PERF-020 (keyset revision, 2026-09-14): newest-first, id as a tiebreaker — same reasoning
  // organization-module's own JpaOrganizationRepository#findKeysetPageOwnedBy already documents.
  @Override
  @SuppressWarnings("PMD.OnlyOneReturn") // three real, distinct exits — first/after/before.
  public KeysetPage<OrganizationClient> findKeysetPageByOrganizationId(
      final UUID organizationId, final KeysetPageRequest pageRequest) {
    final org.springframework.data.domain.PageRequest limit =
        org.springframework.data.domain.PageRequest.of(0, pageRequest.size() + 1);
    if (pageRequest.after() != null) {
      final KeysetCursor cursor = pageRequest.after();
      return SpringDataKeysetPageMapper.forward(
          organizationClients.findPageByOrganizationIdAfter(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          true,
          this::toDomain,
          this::cursorOf);
    }
    if (pageRequest.before() != null) {
      final KeysetCursor cursor = pageRequest.before();
      return SpringDataKeysetPageMapper.backward(
          organizationClients.findPageByOrganizationIdBefore(
              organizationId, cursor.createdAt(), cursor.id(), limit),
          pageRequest.size(),
          this::toDomain,
          this::cursorOf);
    }
    return SpringDataKeysetPageMapper.forward(
        organizationClients.findFirstPageByOrganizationId(organizationId, limit),
        pageRequest.size(),
        false,
        this::toDomain,
        this::cursorOf);
  }

  private KeysetCursor cursorOf(final OrganizationClientEntity entity) {
    return new KeysetCursor(entity.getCreatedAt(), entity.getId());
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
        entity.isActive(),
        entity.getVersion());
  }
}
