package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.VerificationToken;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The wait before another sign-in code, sign-in link or password-reset link is sent to the same
 * Account: asking again inside it sends nothing new and leaves the earlier secret valid, so the
 * person still has the email already in their inbox. Clerk's prebuilt components make people wait
 * the same 30 seconds.
 *
 * <p>It is silent on purpose. The caller is redirected exactly as for any request, so neither the
 * wait nor its absence tells a stranger anything about the address. The per-address and per-IP
 * limits at the HTTP layer bound the total; this stops the double click and the impatient re-send,
 * and the second mail that would otherwise end the first one's usefulness (issuing a new secret
 * ends the earlier ones).
 *
 * <p>{@code verification_tokens} has no issue time, but each service gives every token of a type
 * the same TTL, so a token issued at {@code t} expires at {@code t + ttl}: the issue time is {@code
 * expiresAt - ttl}. If a TTL is ever changed, tokens already issued under the old one are misjudged
 * for at most the cooldown, in either direction, which is harmless.
 */
public final class ResendCooldown {

  /** Seconds a person must wait before another secret is sent to the same Account. */
  public static final Duration WAIT = Duration.ofSeconds(30);

  private ResendCooldown() {
    // Static utility — not instantiable.
  }

  /**
   * @param latestActive the Account's most recent still-usable token of this type, if any
   * @param ttl the lifetime every token of this type is issued with
   * @return whether a secret was issued less than {@link #WAIT} ago
   */
  public static boolean isActive(
      final Optional<VerificationToken> latestActive, final Duration ttl, final Instant now) {
    return latestActive
        .map(token -> token.expiresAt().minus(ttl).isAfter(now.minus(WAIT)))
        .orElse(false);
  }
}
