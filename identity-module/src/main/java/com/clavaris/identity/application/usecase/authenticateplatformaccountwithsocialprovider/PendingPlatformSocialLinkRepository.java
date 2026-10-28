package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import com.clavaris.identity.domain.model.PendingPlatformSocialLink;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link
 * com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository}'s
 * platform-tier sibling — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPendingPlatformSocialLinkRepository}.
 */
public interface PendingPlatformSocialLinkRepository {

  Optional<PendingPlatformSocialLink> findByConfirmationTokenHash(String confirmationTokenHash);

  void save(PendingPlatformSocialLink pendingLink);

  /**
   * Conditional single-row update, the same race guard {@code
   * VerificationTokenRepository#consumeIfActive} gives the other single-use tokens: {@code false}
   * means a concurrent confirmation consumed it (or it expired) first, and the caller must treat
   * the link as invalid. A plain in-memory check followed by a save lets two simultaneous clicks
   * both pass.
   */
  boolean consumeIfActive(UUID linkId, Instant consumedAt);
}
