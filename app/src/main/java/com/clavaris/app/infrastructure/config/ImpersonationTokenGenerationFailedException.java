package com.clavaris.app.infrastructure.config;

/**
 * Thrown by {@link ImpersonationTokenIssuer} when {@code JwtGenerator} returns {@code null} — same
 * underlying failure class (typically: no active signing key for this Organization) {@link
 * RefreshTokenRotationAuthenticationProvider}'s own identical null-check guards against, mapped by
 * {@code GlobalExceptionHandler}'s own catch-all rather than a bespoke handler here — an operator
 * action hitting this is an infrastructure problem worth a correlation id and an ERROR log line,
 * not a caller-input problem worth a specific 4xx.
 */
final class ImpersonationTokenGenerationFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /* package */ ImpersonationTokenGenerationFailedException() {
    super("The token generator failed to generate the impersonation access token");
  }
}
