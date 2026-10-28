package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.PendingSocialLink;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

  /**
   * Ends every still-usable "confirm this social link" request of this Account. The confirmation
   * link was mailed to the old address, and following it would attach a provider identity to the
   * Account; after an email change only the new address's owner may do that.
   *
   * @return how many requests were ended
   */
  int invalidateAllActiveForAccount(AccountId accountId, Instant now);

  /**
   * Conditional single-row update, the same race guard {@code
   * VerificationTokenRepository#consumeIfActive} gives the other single-use tokens: {@code false}
   * means a concurrent confirmation consumed it (or it expired) first, and the caller must treat
   * the link as invalid. A plain in-memory check followed by a save lets two simultaneous clicks
   * both pass.
   */
  boolean consumeIfActive(UUID linkId, Instant consumedAt);
}
