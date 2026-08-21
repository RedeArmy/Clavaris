package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Session;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaSessionRepository}. {@code
 * application.usecase.rotaterefreshtoken.RotateRefreshTokenService} is the second consumer, same
 * precedent as {@code registeraccount.AccountRepository}'s own cross-use-case reuse.
 */
public interface SessionRepository {

  // "id", not "sessionId" — matches java.util.UUID-keyed repository conventions elsewhere in this
  // module (e.g. SigningKeyRepository).
  @SuppressWarnings("PMD.ShortVariable")
  Optional<Session> findById(UUID id);

  void save(Session session);

  /**
   * BR-ID-03: the reuse-detection cascade — every session for the account, not just the one the
   * reused token belonged to.
   */
  void revokeAllActiveForAccount(AccountId accountId);
}
