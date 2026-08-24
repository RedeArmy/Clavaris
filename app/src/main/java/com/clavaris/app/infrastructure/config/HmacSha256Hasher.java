package com.clavaris.app.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The actual HMAC-SHA256-hex-digest primitive shared by {@link BearerTokenHasher} (TD-SEC-019) and
 * {@link RateLimitKeyHasher} (TD-SEC-023) — extracted after a SonarCloud duplication finding on
 * {@code RateLimitKeyHasher} (34.8%, 23 lines) confirmed the two classes had drifted from
 * "deliberately near-identical, not yet worth deduplicating" (their own original Javadoc reasoning)
 * into a real, tooling-flagged code smell. Package-private, {@code app}-local, not a promotion to
 * {@code common}: this is still one call site (this package's own two callers), not the "third
 * module needs the same thing" threshold TD-ARCH-001 established — deduplicating within one package
 * is a different, smaller move than promoting to the shared kernel.
 *
 * <p>{@link BearerTokenHasher}/{@link RateLimitKeyHasher} stay separate, deliberately thin wrapper
 * types around this, rather than every caller constructing an {@code HmacSha256Hasher} directly
 * with whichever secret happens to be in scope — see each wrapper's own Javadoc for why the two
 * secrets (bearer-token vs. rate-limit-key) must never be interchangeable at a call site, which a
 * bare shared type with no domain identity would no longer prevent at compile time.
 */
final class HmacSha256Hasher {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  /* package */ HmacSha256Hasher(final String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("secret must not be blank");
    }
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
  }

  /**
   * A fresh {@link Mac} instance per call, not one shared/reused field — {@code Mac} is stateful
   * across its own {@code init}/{@code doFinal} sequence and explicitly not safe for concurrent use
   * from multiple threads on the same instance (its own Javadoc), and both callers of this class
   * call {@code hash} from every request thread they handle.
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
