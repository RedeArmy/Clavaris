package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaOrganizationSocialCredentialRepository}. {@link
 * #findByOrganizationIdAndProvider} returning empty is the normal state for any {@code
 * (organizationId, provider)} pair that has never had its own credentials set — see {@link
 * OrganizationSocialCredential}'s own Javadoc.
 */
public interface OrganizationSocialCredentialRepository {

  Optional<OrganizationSocialCredential> findByOrganizationIdAndProvider(
      UUID organizationId, SocialProvider provider);

  List<OrganizationSocialCredential> findAllByOrganizationId(UUID organizationId);

  void save(OrganizationSocialCredential credential);

  void deleteByOrganizationIdAndProvider(UUID organizationId, SocialProvider provider);
}
