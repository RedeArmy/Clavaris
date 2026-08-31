package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import com.clavaris.identity.domain.model.PendingPlatformSocialLink;
import java.util.Optional;

/**
 * {@link
 * com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository}'s
 * platform-tier sibling — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPendingPlatformSocialLinkRepository}.
 */
public interface PendingPlatformSocialLinkRepository {

  // PMD.LongVariable: confirmationTokenHash names exactly what it is, same convention
  // PendingSocialLinkRepository's own identical suppression already documents.
  @SuppressWarnings("PMD.LongVariable")
  Optional<PendingPlatformSocialLink> findByConfirmationTokenHash(String confirmationTokenHash);

  void save(PendingPlatformSocialLink pendingLink);
}
