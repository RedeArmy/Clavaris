package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.domain.service.HighEntropyClientSecret;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies the client secret presented at {@code /oauth2/token}, choosing the check by how the
 * stored hash was written.
 *
 * <ul>
 *   <li>{@code {sha256}...}: a secret the server generated (256 random bits). Checked with one
 *       SHA-256 and a constant-time compare, microseconds, no bulkhead needed. This is what every
 *       client registered or rotated since {@link HighEntropyClientSecret} exists has, and it is
 *       why {@code /oauth2/token} no longer pays an Argon2id verification per request.
 *   <li>{@code $argon2...}: a secret hashed before that, and the platform client's operator-chosen
 *       bootstrap secret, which is the one secret a person picks and so must stay memory-hard.
 *       Verified through {@link Argon2BulkheadPasswordEncoder}, so the bounded-concurrency
 *       protection of TD-FUT-017 still covers exactly the requests that are expensive.
 *   <li>Anything else: not a hash this server wrote, so never a match.
 * </ul>
 *
 * <p>An old Argon2 hash is not rewritten on a successful check. The edge Spring Authorization
 * Server offers for that ({@link #upgradeEncoding}, which makes it call {@code
 * RegisteredClientRepository#save}) needs a write path these repositories deliberately do not
 * expose, so it must answer {@code false} or every legacy client would fail to authenticate. Such a
 * client moves to the fast scheme the next time its secret is rotated, which is also when a new
 * secret is generated; a stored hash cannot be converted, only replaced, because the raw secret is
 * not kept.
 *
 * <p>{@link #encode} is only ever reached for a secret of unknown origin, so it keeps the safe
 * default (Argon2id); client registration and rotation hash through {@code
 * GeneratedClientSecretHasher}, not through here.
 */
final class ClientSecretPasswordEncoder implements PasswordEncoder {

  private static final String ARGON2_PREFIX = "$argon2";

  private final Argon2BulkheadPasswordEncoder argon2;

  /* package */ ClientSecretPasswordEncoder(final Argon2BulkheadPasswordEncoder argon2) {
    this.argon2 = argon2;
  }

  @Override
  public String encode(final CharSequence rawPassword) {
    return argon2.encode(rawPassword);
  }

  @Override
  public boolean matches(final CharSequence rawPassword, final String encodedPassword) {
    final boolean matched;
    if (HighEntropyClientSecret.isHash(encodedPassword)) {
      matched = HighEntropyClientSecret.matches(rawPassword.toString(), encodedPassword);
    } else if (encodedPassword != null && encodedPassword.startsWith(ARGON2_PREFIX)) {
      matched = argon2.matches(rawPassword, encodedPassword);
    } else {
      matched = false;
    }
    return matched;
  }

  @Override
  public boolean upgradeEncoding(final String encodedPassword) {
    return false;
  }
}
