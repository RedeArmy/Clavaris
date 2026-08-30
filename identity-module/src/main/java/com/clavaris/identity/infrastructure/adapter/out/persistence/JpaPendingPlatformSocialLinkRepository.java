package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PendingPlatformSocialLinkRepository;
import com.clavaris.identity.domain.model.PendingPlatformSocialLink;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.PendingPlatformSocialLink}
 * (framework-free) and {@link PendingPlatformSocialLinkEntity}.
 *
 * <p>PMD.LongVariable: {@code confirmationTokenHash} names exactly what it is, same convention
 * {@code JpaPendingSocialLinkRepository}'s own identical suppression already documents.
 */
@SuppressWarnings("PMD.LongVariable")
@Repository
class JpaPendingPlatformSocialLinkRepository implements PendingPlatformSocialLinkRepository {

  private final SpringDataPendingPlatformSocialLinkJpaRepository pendingLinks;

  /* package */ JpaPendingPlatformSocialLinkRepository(
      final SpringDataPendingPlatformSocialLinkJpaRepository pendingLinks) {
    this.pendingLinks = pendingLinks;
  }

  @Override
  public Optional<PendingPlatformSocialLink> findByConfirmationTokenHash(
      final String confirmationTokenHash) {
    return pendingLinks.findByConfirmationTokenHash(confirmationTokenHash).map(this::toDomain);
  }

  @Override
  public void save(final PendingPlatformSocialLink pendingLink) {
    pendingLinks.save(
        new PendingPlatformSocialLinkEntity(
            pendingLink.id(),
            pendingLink.platformAccountId().value(),
            pendingLink.provider().name(),
            pendingLink.providerUserId(),
            pendingLink.confirmationTokenHash(),
            pendingLink.expiresAt(),
            pendingLink.consumedAt().orElse(null)));
  }

  private PendingPlatformSocialLink toDomain(final PendingPlatformSocialLinkEntity entity) {
    return PendingPlatformSocialLink.reconstitute(
        entity.getId(),
        new PlatformAccountId(entity.getPlatformAccountId()),
        SocialProvider.valueOf(entity.getProvider()),
        entity.getProviderUserId(),
        entity.getConfirmationTokenHash(),
        entity.getExpiresAt(),
        entity.getConsumedAt());
  }
}
