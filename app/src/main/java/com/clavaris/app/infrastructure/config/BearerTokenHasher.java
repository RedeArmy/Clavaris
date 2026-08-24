package com.clavaris.app.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TD-SEC-019: a deterministic, keyed digest for the bearer token values {@link
 * HashedTokenOAuth2AuthorizationService} stores in {@code oauth2_authorization} — HMAC-SHA256, not
 * plain SHA-256, and deliberately not Argon2id (ADR-0005's own choice for {@code
 * password_credentials}): a lookup-by-value column needs the exact same input to always produce the
 * exact same output, which rules out Argon2id's per-call random salt entirely — this is the same
 * class of "keyed, not salted, because the caller must reproduce it on lookup" requirement {@code
 * refresh_tokens.token_hash} and {@code verification_tokens.token_hash} already have, confirmed by
 * reading {@code RotateRefreshTokenService}'s own hash-then-compare logic. Keyed (HMAC), not plain
 * SHA-256 (the gap TD-SEC-023 separately names for rate-limit Redis keys): a bearer token is itself
 * the credential, not merely a correlatable identifier — an offline dictionary attack against a
 * compromised Postgres backup is exactly the threat this row exists to close, and a keyed digest is
 * what makes that infeasible without the server-side secret too.
 *
 * <p>Deliberately in {@code app}, not {@code common}: this is currently one consumer ({@link
 * HashedTokenOAuth2AuthorizationService}), not the "third module needs the same thing" threshold
 * this codebase's own YAGNI convention requires before promoting a utility into the shared kernel
 * (TD-ARCH-001's own precedent) — TD-SEC-023 fixing the same way would be the second, still not the
 * third.
 */
final class BearerTokenHasher {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  /* package */ BearerTokenHasher(final String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("secret must not be blank");
    }
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
  }

  /**
   * A fresh {@link Mac} instance per call, not one shared/reused field — {@code Mac} is stateful
   * across its own {@code init}/{@code doFinal} sequence and explicitly not safe for concurrent use
   * from multiple threads on the same instance (its own Javadoc), and this is called from every
   * request thread issuing or revoking a token.
   */
  /* package */ String hash(final String value) {
    try {
      final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is a JCA standard algorithm name every conforming JVM ships, and `key` is
      // validated non-blank in the constructor — this can only fail from a fatally misconfigured
      // JVM, never from caller-controlled input, so a checked-exception-shaped API here would only
      // push an unhandleable case onto every caller.
      throw new IllegalStateException("Unable to compute HMAC-SHA256", e);
    }
  }
}
