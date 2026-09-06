package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.clientregistry.domain.model.DomainVerificationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update — same "id is always a
 * real, already-assigned UUID, no {@code @GeneratedValue}" reasoning as {@code
 * JpaRedirectPolicyRepository}'s own identical Javadoc. {@code mode}/{@code verificationStatus}
 * round-trip via {@code name()}/{@code valueOf()} — same plain-string-enum convention {@code
 * ClientDomainConfigEntity}'s own Javadoc documents.
 */
@Repository
class JpaClientDomainConfigRepository implements ClientDomainConfigRepository {

  private final SpringDataClientDomainConfigJpaRepository domainConfigs;

  /* package */ JpaClientDomainConfigRepository(
      final SpringDataClientDomainConfigJpaRepository domainConfigs) {
    this.domainConfigs = domainConfigs;
  }

  @Override
  public Optional<ClientDomainConfig> findByOAuthClientId(final UUID oauthClientId) {
    return domainConfigs.findByOauthClientId(oauthClientId).map(this::toDomain);
  }

  @Override
  public Optional<ClientDomainConfig> findByHostname(final String hostname) {
    return domainConfigs.findByHostname(hostname).map(this::toDomain);
  }

  @Override
  public void save(final ClientDomainConfig config) {
    domainConfigs.save(
        new ClientDomainConfigEntity(
            config.id(),
            config.oauthClientId(),
            config.mode().map(Enum::name).orElse(null),
            config.hostname().orElse(null),
            config.verificationStatus().map(Enum::name).orElse(null),
            config.dnsTxtChallengeToken().orElse(null),
            config.embeddingOrigin().orElse(null),
            config.verifiedAt().orElse(null),
            config.createdAt(),
            config.updatedAt()));
  }

  private ClientDomainConfig toDomain(final ClientDomainConfigEntity entity) {
    return ClientDomainConfig.reconstitute(
        entity.getId(),
        entity.getOauthClientId(),
        entity.getMode() == null ? null : ClientDomainMode.valueOf(entity.getMode()),
        entity.getHostname(),
        entity.getVerificationStatus() == null
            ? null
            : DomainVerificationStatus.valueOf(entity.getVerificationStatus()),
        entity.getDnsTxtChallengeToken(),
        entity.getEmbeddingOrigin(),
        entity.getVerifiedAt(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
