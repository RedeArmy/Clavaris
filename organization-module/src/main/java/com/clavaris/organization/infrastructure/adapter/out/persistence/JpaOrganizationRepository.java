package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
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
        new OrganizationEntity(organization.id(), organization.name(), organization.createdAt()));
  }

  @Override
  public boolean existsById(final UUID organizationId) {
    return organizations.existsById(organizationId);
  }
}
