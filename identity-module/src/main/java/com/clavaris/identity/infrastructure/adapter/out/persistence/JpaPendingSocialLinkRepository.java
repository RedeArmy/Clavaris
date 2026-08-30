package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.PendingSocialLink;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port; maps between {@code domain.model.PendingSocialLink}
 * (framework-free) and {@link PendingSocialLinkEntity}.
 *
 * <p>PMD.LongVariable: {@code confirmationTokenHash} names exactly what it is, same convention
 * {@code PendingSocialLink}'s own class-level suppression already documents for this exact name.
 */
@SuppressWarnings("PMD.LongVariable")
@Repository
class JpaPendingSocialLinkRepository implements PendingSocialLinkRepository {

  private final SpringDataPendingSocialLinkJpaRepository pendingLinks;

  /* package */ JpaPendingSocialLinkRepository(
      final SpringDataPendingSocialLinkJpaRepository pendingLinks) {
    this.pendingLinks = pendingLinks;
  }

  @Override
  public Optional<PendingSocialLink> findByConfirmationTokenHash(
      final String confirmationTokenHash) {
    return pendingLinks.findByConfirmationTokenHash(confirmationTokenHash).map(this::toDomain);
  }

  @Override
  public void save(final PendingSocialLink pendingLink) {
    pendingLinks.save(
        new PendingSocialLinkEntity(
            pendingLink.id(),
            pendingLink.accountId().value(),
            pendingLink.provider().name(),
            pendingLink.providerUserId(),
            pendingLink.confirmationTokenHash(),
            pendingLink.expiresAt(),
            pendingLink.consumedAt().orElse(null)));
  }

  private PendingSocialLink toDomain(final PendingSocialLinkEntity entity) {
    return PendingSocialLink.reconstitute(
        entity.getId(),
        new AccountId(entity.getAccountId()),
        SocialProvider.valueOf(entity.getProvider()),
        entity.getProviderUserId(),
        entity.getConfirmationTokenHash(),
        entity.getExpiresAt(),
        entity.getConsumedAt());
  }
}
