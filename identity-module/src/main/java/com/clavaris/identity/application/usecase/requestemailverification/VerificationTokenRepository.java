package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.VerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

  /**
   * SDE-III review (2026-09-15): the guard against the same TOCTOU race {@code
   * RefreshTokenRepository#revokeIfActive} closes for refresh-token rotation (SDE-III review,
   * 2026-09-14) — every confirm use case that reads a token via {@link #findByTokenHash}, checks
   * {@link VerificationToken#isActive()} in memory, then calls {@link VerificationToken#consume()}
   * followed by a plain {@link #save} leaves a window open: two concurrent confirm requests
   * presenting the same still-active token (a double-submitted password-reset form, a token an
   * attacker raced against the legitimate holder) could both pass the in-memory check and both
   * "succeed", since neither request's read sees the other's not-yet-committed write.
   *
   * <p>A conditional update ({@code WHERE id = ? AND consumed_at IS NULL AND expires_at > ?}), not
   * a plain {@code save} after an in-memory {@code consume()} — under READ COMMITTED, the loser of
   * two concurrent calls against the same row is forced to re-check this WHERE clause against the
   * winner's already-committed consumption before it can report how many rows it touched, so it
   * reliably observes zero.
   *
   * @return {@code true} if this call actually consumed the token (this call won the race); {@code
   *     false} if it was already consumed, or had expired, by the time this ran — the caller MUST
   *     treat {@code false} exactly like an invalid token, never silently proceed as if it won.
   */
  boolean consumeIfActive(UUID tokenId, Instant consumedAt);
}
