package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.Organization} and {@link
 * OrganizationEntity}.
 */
@Repository
class JpaOrganizationRepository implements OrganizationRepository {

  private final SpringDataOrganizationJpaRepository organizations;

  /* package */ JpaOrganizationRepository(final SpringDataOrganizationJpaRepository organizations) {
    this.organizations = organizations;
  }

  @Override
  public void save(final Organization organization) {
    organizations.save(
        new OrganizationEntity(
            organization.id(),
            organization.name(),
            organization.createdAt(),
            organization.ownerPlatformAccountId()));
  }

  @Override
  public boolean existsById(final UUID organizationId) {
    return organizations.existsById(organizationId);
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
        entity.getOwnerPlatformAccountId());
  }
}
