package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
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

  /**
   * Ends every still-usable token of this Account (password reset, email verification, sign-in code
   * and link, device-trust challenge). Used when the address they were sent to stops being the
   * Account's: whoever still reads the old mailbox must not be able to finish a reset that was
   * started before the change.
   *
   * @return how many tokens were ended
   */
  int invalidateAllActiveForAccount(AccountId accountId, Instant now);

  /**
   * Ends every still-usable token of one {@code type} for this Account, leaving its other types
   * alone. Issuing a new sign-in code or link ends the previous ones, so only the latest can be
   * used; the broader {@link #invalidateAllActiveForAccount} would also cancel a password reset or
   * an email verification the person has under way, which asking to sign in must not do.
   *
   * @return how many tokens were ended
   */
  int invalidateActiveOfTypeForAccount(
      AccountId accountId, VerificationTokenType type, Instant now);

  /**
   * The Account's still-usable token of this {@code type} that expires last, i.e. the most recently
   * issued one (a type has a single TTL), if any. Used to decide whether a new secret was asked for
   * too soon after the last one ({@code ResendCooldown}).
   */
  Optional<VerificationToken> findLatestActiveOfTypeForAccount(
      AccountId accountId, VerificationTokenType type, Instant now);
}
