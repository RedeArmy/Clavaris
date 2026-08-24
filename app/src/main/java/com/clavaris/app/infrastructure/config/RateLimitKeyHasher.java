package com.clavaris.app.infrastructure.config;

/**
 * TD-SEC-023: a deterministic, keyed digest for the rate-limit identifiers (an email, an IP, a
 * {@code client_id}) {@link AntiAbuseRateLimitingFilter} folds into a Redis key — HMAC-SHA256, not
 * the plain, unsalted SHA-256 this class replaced. A plain digest of a known-format, low-entropy
 * value is reversible via an offline dictionary/rainbow-table attack by anyone who can read the
 * Redis keyspace (an operator, a future read replica, a Redis {@code RDB}/{@code AOF} backup) — the
 * same class of gap {@link BearerTokenHasher}/TD-SEC-019 already closed for {@code
 * oauth2_authorization}, at meaningfully lower stakes here since the value recovered is a
 * correlatable identifier, not a bearer credential, but real enough to fix at near-zero cost.
 *
 * <p>A thin wrapper around {@link HmacSha256Hasher} — see that class's own Javadoc for why the
 * actual HMAC-SHA256 mechanics live there (a SonarCloud duplication finding against this class's
 * own first version, which duplicated {@link BearerTokenHasher}'s implementation almost verbatim)
 * while this type still exists as its own distinct class: keyed by {@code
 * clavaris.rate-limit.key-hash-secret}, never {@link BearerTokenHasher}'s {@code
 * clavaris.oauth2.token-hash-secret} reused — the two hash different kinds of values for different
 * purposes (a bearer credential whose compromise means direct account/token takeover, versus a
 * rate-limit identifier whose compromise only re-exposes information already visible in-band — the
 * login form itself, or server access logs). Reusing one secret across both would mean a compromise
 * of the lower-stakes one also weakens the higher-stakes one, for no real benefit — the two secrets
 * rotate independently instead, and keeping this a distinct type (not a bare {@code
 * HmacSha256Hasher} constructed inline at each call site) is what makes the two non-interchangeable
 * at compile time, not just by convention.
 */
final class RateLimitKeyHasher {

  private final HmacSha256Hasher delegate;

  /* package */ RateLimitKeyHasher(final String secret) {
    this.delegate = new HmacSha256Hasher(secret);
  }

  /* package */ String hash(final String value) {
    return delegate.hash(value);
  }
}
