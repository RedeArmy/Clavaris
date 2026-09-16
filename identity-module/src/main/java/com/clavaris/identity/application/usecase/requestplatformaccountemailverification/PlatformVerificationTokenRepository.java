package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import com.clavaris.identity.domain.model.PlatformVerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaPlatformVerificationTokenRepository}. Parked under
 * {@code requestplatformaccountemailverification} because that's the first use case that needs it —
 * {@code confirmplatformaccountemailverification}/{@code requestplatformaccountpasswordreset}/
 * {@code confirmplatformaccountpasswordreset} are later consumers, same precedent as {@code
 * requestemailverification.VerificationTokenRepository}'s own tenant-tier equivalent.
 */
public interface PlatformVerificationTokenRepository {

  Optional<PlatformVerificationToken> findByTokenHash(String tokenHash);

  void save(PlatformVerificationToken token);

  /**
   * Mirrors {@code requestemailverification.VerificationTokenRepository#consumeIfActive} exactly —
   * same TOCTOU, same fix, same conditional-update mechanics — for the platform tier's own {@code
   * confirmplatformaccountemailverification}/{@code confirmplatformaccountpasswordreset} pair. See
   * that method's own Javadoc for the full rationale.
   *
   * @return {@code true} if this call actually consumed the token; {@code false} if it was already
   *     consumed or had expired — the caller MUST treat {@code false} exactly like an invalid
   *     token.
   */
  boolean consumeIfActive(UUID tokenId, Instant consumedAt);
}
