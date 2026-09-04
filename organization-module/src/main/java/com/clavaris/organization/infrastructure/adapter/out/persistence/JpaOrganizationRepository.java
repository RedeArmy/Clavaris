package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements the outbound port; maps between {@code domain.model.Organization} and {@link
 * OrganizationEntity}. {@code allowedSocialProviders} (ADR-0020) is (de)serialized here, same
 * {@link ObjectMapper}-injection convention {@code JpaOAuthClientRepository}'s own {@code
 * allowedScopes} column already establishes — null-safe, unlike that class's own helper, since an
 * Organization that has never configured social login has a genuinely {@code NULL} column, not
 * merely an empty JSON array.
 */
@Repository
class JpaOrganizationRepository implements OrganizationRepository {

  private final SpringDataOrganizationJpaRepository organizations;
  private final ObjectMapper objectMapper;

  /* package */ JpaOrganizationRepository(
      final SpringDataOrganizationJpaRepository organizations, final ObjectMapper objectMapper) {
    this.organizations = organizations;
    this.objectMapper = objectMapper;
  }

  @Override
  public void save(final Organization organization) {
    organizations.save(
        new OrganizationEntity(
            organization.id(),
            organization.name(),
            organization.createdAt(),
            organization.ownerPlatformAccountId(),
            organization.socialLoginEnabled(),
            writeJsonArray(organization.allowedSocialProviders()),
            organization.environment().name(),
            organization.linkedEnvironmentOrganizationId().orElse(null)));
  }

  @Override
  public boolean existsById(final UUID organizationId) {
    return organizations.existsById(organizationId);
  }

  @Override
  public Optional<Organization> findById(final UUID organizationId) {
    return organizations.findById(organizationId).map(this::toDomain);
  }

  @Override
  @SuppressWarnings("PMD.LongVariable")
  public List<Organization> findAllOwnedBy(final UUID ownerPlatformAccountId) {
    return organizations.findAllByOwnerPlatformAccountId(ownerPlatformAccountId).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void deleteById(final UUID organizationId) {
    // .flush(), same "must actually reach Postgres now, not deferred to whenever the surrounding
    // transaction happens to commit" reasoning as identity-module's own JpaAccountRepository
    // .deleteById — a real footgun otherwise for any caller reading this same row through a
    // different connection/path within the same transaction.
    organizations.deleteById(organizationId);
    organizations.flush();
  }

  private Organization toDomain(final OrganizationEntity entity) {
    return Organization.reconstitute(
        entity.getId(),
        entity.getName(),
        entity.getCreatedAt(),
        entity.getOwnerPlatformAccountId(),
        entity.isSocialLoginEnabled(),
        readJsonArray(entity.getAllowedSocialProviders()),
        OrganizationEnvironment.valueOf(entity.getEnvironment()),
        entity.getLinkedEnvironmentOrganizationId());
  }

  private String writeJsonArray(final List<String> values) {
    return values.isEmpty() ? null : objectMapper.writeValueAsString(values);
  }

  private List<String> readJsonArray(final String json) {
    return json == null ? List.of() : Arrays.asList(objectMapper.readValue(json, String[].class));
  }
}
