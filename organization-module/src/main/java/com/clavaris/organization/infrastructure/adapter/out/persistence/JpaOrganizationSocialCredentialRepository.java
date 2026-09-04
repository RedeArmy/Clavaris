package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update — same "id is always a
 * real, already-assigned UUID by the time this is called" reasoning {@code
 * JpaRateLimitPolicyRepository}'s own identical Javadoc already establishes.
 */
@Repository
class JpaOrganizationSocialCredentialRepository implements OrganizationSocialCredentialRepository {

  private final SpringDataOrganizationSocialCredentialJpaRepository credentials;

  /* package */ JpaOrganizationSocialCredentialRepository(
      final SpringDataOrganizationSocialCredentialJpaRepository credentials) {
    this.credentials = credentials;
  }

  @Override
  public Optional<OrganizationSocialCredential> findByOrganizationIdAndProvider(
      final UUID organizationId, final SocialProvider provider) {
    return credentials
        .findByOrganizationIdAndProvider(organizationId, provider)
        .map(this::toDomain);
  }

  @Override
  public List<OrganizationSocialCredential> findAllByOrganizationId(final UUID organizationId) {
    return credentials.findAllByOrganizationId(organizationId).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void save(final OrganizationSocialCredential credential) {
    credentials.save(
        new OrganizationSocialCredentialEntity(
            credential.id(),
            credential.organizationId(),
            credential.provider(),
            credential.clientId(),
            credential.clientSecretEncrypted(),
            credential.createdAt(),
            credential.updatedAt()));
  }

  @Override
  public void deleteByOrganizationIdAndProvider(
      final UUID organizationId, final SocialProvider provider) {
    credentials.deleteByOrganizationIdAndProvider(organizationId, provider);
  }

  private OrganizationSocialCredential toDomain(final OrganizationSocialCredentialEntity entity) {
    return OrganizationSocialCredential.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getProvider(),
        entity.getClientId(),
        entity.getClientSecretEncrypted(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
