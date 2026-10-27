package com.clavaris.identity.application.usecase.requestemailchange;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code JpaEmailChangeRequestRepository}. Shared with {@code
 * confirmemailchange}, same "parked under the first use case that needs it" precedent as {@code
 * VerificationTokenRepository}.
 */
public interface EmailChangeRequestRepository {

  /** One pending request per account: any earlier one is dropped, so only the newest link works. */
  void replaceFor(EmailChangeRequest request);

  Optional<EmailChangeRequest> findByTokenHash(String tokenHash);

  /**
   * Conditional single-row update, the same race guard as {@code
   * VerificationTokenRepository#consumeIfActive}: {@code false} means another request consumed it
   * (or it expired) first and the caller must treat the link as invalid.
   */
  boolean consumeIfActive(UUID requestId, Instant consumedAt);

  void deleteAllForAccount(AccountId accountId);
}
