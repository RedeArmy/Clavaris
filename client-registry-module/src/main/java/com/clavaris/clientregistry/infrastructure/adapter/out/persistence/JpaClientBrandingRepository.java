package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.setclientbranding.ClientBrandingRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update, same rationale as {@code
 * JpaRedirectPolicyRepository}'s own identical Javadoc.
 */
@Repository
class JpaClientBrandingRepository implements ClientBrandingRepository {

  private final SpringDataClientBrandingJpaRepository brandings;

  /* package */ JpaClientBrandingRepository(final SpringDataClientBrandingJpaRepository brandings) {
    this.brandings = brandings;
  }

  @Override
  public Optional<ClientBranding> findByOAuthClientId(final UUID oauthClientId) {
    return brandings.findByOauthClientId(oauthClientId).map(this::toDomain);
  }

  @Override
  public void save(final ClientBranding branding) {
    brandings.save(
        new ClientBrandingEntity(
            branding.id(),
            branding.oauthClientId(),
            branding.logoUrl().orElse(null),
            branding.primaryColor().orElse(null),
            branding.applicationDisplayName().orElse(null),
            branding.createdAt(),
            branding.updatedAt()));
  }

  private ClientBranding toDomain(final ClientBrandingEntity entity) {
    return ClientBranding.reconstitute(
        entity.getId(),
        entity.getOauthClientId(),
        entity.getLogoUrl(),
        entity.getPrimaryColor(),
        entity.getApplicationDisplayName(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
