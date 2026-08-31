package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.PendingSocialLink;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPendingSocialLinkRepository}. {@code
 * confirmationTokenHash} is globally unique by construction (same hash-only-token shape as {@code
 * VerificationTokenRepository.findByTokenHash}), which is what lets {@code
 * ConfirmPendingSocialLinkService} resolve a presented raw token straight to its row.
 */
public interface PendingSocialLinkRepository {

  // PMD.LongVariable: confirmationTokenHash names exactly what it is, same convention
  // PendingSocialLink's own class-level suppression already documents for this exact name.
  @SuppressWarnings("PMD.LongVariable")
  Optional<PendingSocialLink> findByConfirmationTokenHash(String confirmationTokenHash);

  void save(PendingSocialLink pendingLink);
}
