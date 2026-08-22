package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import com.clavaris.identity.domain.model.PlatformVerificationToken;
import java.util.Optional;

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
}
