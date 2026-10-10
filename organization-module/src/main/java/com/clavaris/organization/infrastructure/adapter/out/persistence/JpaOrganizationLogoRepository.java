package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationLogoRepository;
import com.clavaris.organization.domain.model.OrganizationLogo;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaOrganizationLogoRepository implements OrganizationLogoRepository {

  private final SpringDataOrganizationLogoJpaRepository logos;

  /* package */ JpaOrganizationLogoRepository(final SpringDataOrganizationLogoJpaRepository logos) {
    this.logos = logos;
  }

  @Override
  public Optional<OrganizationLogo> findByOrganizationId(final UUID organizationId) {
    return logos
        .findById(organizationId)
        .map(
            entity ->
                OrganizationLogo.reconstitute(
                    entity.getOrganizationId(), entity.getContentType(), entity.getContent()));
  }

  @Override
  public void save(final OrganizationLogo logo) {
    logos.save(
        new OrganizationLogoEntity(logo.organizationId(), logo.contentType(), logo.content()));
  }

  @Override
  public void deleteByOrganizationId(final UUID organizationId) {
    if (logos.existsById(organizationId)) {
      logos.deleteById(organizationId);
    }
  }
}
