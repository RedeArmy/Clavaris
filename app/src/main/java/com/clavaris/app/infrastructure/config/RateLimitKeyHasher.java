package com.clavaris.app.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TD-SEC-023: a deterministic, keyed digest for the rate-limit identifiers (an email, an IP, a
 * {@code client_id}) {@link AntiAbuseRateLimitingFilter} folds into a Redis key — HMAC-SHA256, not
 * the plain, unsalted SHA-256 this class replaces. A plain digest of a known-format, low-entropy
 * value is reversible via an offline dictionary/rainbow-table attack by anyone who can read the
 * Redis keyspace (an operator, a future read replica, a Redis {@code RDB}/{@code AOF} backup) — the
 * same class of gap {@link BearerTokenHasher}/TD-SEC-019 already closed for {@code
 * oauth2_authorization}, at meaningfully lower stakes here since the value recovered is a
 * correlatable identifier, not a bearer credential, but real enough to fix at near-zero cost.
 *
 * <p>Deliberately its own class with its own secret ({@code clavaris.rate-limit.key-hash-secret}),
 * not a second caller of {@link BearerTokenHasher} reusing {@code
 * clavaris.oauth2.token-hash-secret}: the two hash genuinely different kinds of values for
 * genuinely different purposes (a bearer credential whose compromise means direct account/token
 * takeover, versus a rate-limit identifier whose compromise only re-exposes information already
 * visible in-band — the login form itself, or server access logs). Reusing one secret across both
 * would mean a compromise of the lower-stakes one also weakens the higher-stakes one, for no real
 * benefit — the two secrets rotate independently instead. Structurally identical to {@link
 * BearerTokenHasher} otherwise (same HMAC-SHA256-hex-digest shape) — deliberately not deduplicated
 * into one shared utility yet: two near-identical small classes across the same package is a
 * reasonable place to stop before a third real consumer justifies extracting one, the same "not yet
 * the third module" threshold {@code common}/TD-ARCH-001 already established for this codebase.
 */
final class RateLimitKeyHasher {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  /* package */ RateLimitKeyHasher(final String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("secret must not be blank");
    }
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
  }

  /**
   * A fresh {@link Mac} instance per call, not one shared/reused field — same thread-safety
   * reasoning as {@link BearerTokenHasher#hash(String)}: {@code Mac} is explicitly not safe for
   * concurrent use from multiple threads on the same instance, and this runs on every request
   * thread this filter evaluates a rule for.
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
