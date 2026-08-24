package com.clavaris.app.infrastructure.config;

/**
 * TD-SEC-019: a deterministic, keyed digest for the bearer token values {@link
 * HashedTokenOAuth2AuthorizationService} stores in {@code oauth2_authorization} — HMAC-SHA256, not
 * plain SHA-256, and deliberately not Argon2id (ADR-0005's own choice for {@code
 * password_credentials}): a lookup-by-value column needs the exact same input to always produce the
 * exact same output, which rules out Argon2id's per-call random salt entirely — this is the same
 * class of "keyed, not salted, because the caller must reproduce it on lookup" requirement {@code
 * refresh_tokens.token_hash} and {@code verification_tokens.token_hash} already have, confirmed by
 * reading {@code RotateRefreshTokenService}'s own hash-then-compare logic. Keyed (HMAC), not plain
 * SHA-256 (the gap TD-SEC-023 separately closed for rate-limit Redis keys): a bearer token is
 * itself the credential, not merely a correlatable identifier — an offline dictionary attack
 * against a compromised Postgres backup is exactly the threat this row exists to close, and a keyed
 * digest is what makes that infeasible without the server-side secret too.
 *
 * <p>A thin wrapper around {@link HmacSha256Hasher} — see that class's own Javadoc for why the
 * actual HMAC-SHA256 mechanics live there (a SonarCloud duplication finding) while this type still
 * exists as its own distinct class: keyed by {@code clavaris.oauth2.token-hash-secret}, never
 * {@link RateLimitKeyHasher}'s {@code clavaris.rate-limit.key-hash-secret} — the two hash different
 * kinds of values at different stakes (a bearer credential vs. a correlatable identifier), and one
 * secret backing both would let a compromise of the lower-stakes one weaken the higher-stakes one
 * for no benefit. Keeping this a distinct type, not a bare {@code HmacSha256Hasher} constructed
 * inline at each call site, is what makes the two secrets non-interchangeable at compile time, not
 * just by convention.
 */
final class BearerTokenHasher {

  private final HmacSha256Hasher delegate;

  /* package */ BearerTokenHasher(final String secret) {
    this.delegate = new HmacSha256Hasher(secret);
  }

  /* package */ String hash(final String value) {
    return delegate.hash(value);
  }
}
