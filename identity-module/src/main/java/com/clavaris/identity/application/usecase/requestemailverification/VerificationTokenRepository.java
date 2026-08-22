package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.VerificationToken;
import java.util.Optional;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaVerificationTokenRepository}. Parked under {@code
 * requestemailverification} because that's the first use case that needs it — {@code
 * confirmemailverification}/{@code requestpasswordreset}/{@code confirmpasswordreset} are later
 * consumers of the same port, same precedent as {@code registeraccount.EventOutboxWriter}.
 */
public interface VerificationTokenRepository {

  /**
   * {@code token_hash} is globally unique (data-model.md §3) across both {@link
   * com.clavaris.identity.domain.model.VerificationTokenType} values — a confirm use case still
   * checks {@code type} itself after this lookup (defense in depth: a password-reset token must
   * never confirm an email, and vice versa, even though collision is already astronomically
   * unlikely by construction).
   */
  Optional<VerificationToken> findByTokenHash(String tokenHash);

  void save(VerificationToken token);
}
