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

  Optional<PendingSocialLink> findByConfirmationTokenHash(String confirmationTokenHash);

  void save(PendingSocialLink pendingLink);
}
