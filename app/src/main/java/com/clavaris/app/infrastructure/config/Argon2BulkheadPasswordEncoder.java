package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.CpuBoundVerificationGate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

/**
 * TD-FUT-017: a {@link PasswordEncoder} decorator bounding concurrent Argon2id {@code matches}
 * calls through the shared {@link CpuBoundVerificationGate} — installed onto {@code
 * ClientSecretAuthenticationProvider} by {@link Argon2ClientAuthenticationSupport}, the exact call
 * site `load-testing/README.md` §2 measured as {@code /oauth2/token}'s real concurrency ceiling.
 *
 * <p><b>Throws a real {@code OAuth2AuthenticationException}, not a generic exception</b> — verified
 * against the actual resolved {@code spring-security-oauth2-authorization-server:7.1.1} jar via
 * {@code javap}, not assumed: {@code ClientSecretAuthenticationProvider.authenticate()} has no
 * {@code try/catch} around its own {@code passwordEncoder.matches(...)} call, so any {@code
 * AuthenticationException} subtype propagates straight out of it; {@code
 * OAuth2ClientAuthenticationFilter.doFilterInternal}'s own exception table, in turn, only catches
 * {@code OAuth2AuthenticationException} specifically (not the broader {@code
 * AuthenticationException}) before routing to its configured {@code authenticationFailureHandler} —
 * a plain {@code AuthenticationServiceException} would miss that catch block entirely and fall
 * through to generic servlet error handling instead of a real, spec-shaped OAuth2 error response.
 * {@link OAuth2ErrorCodes#SERVER_ERROR} is the correct RFC 6749 §5.2 token-endpoint error code for
 * "the server encountered an unexpected condition" — there is no token-endpoint-scoped equivalent
 * of the authorization endpoint's {@code temporarily_unavailable} code to reach for instead.
 *
 * <p>{@code encode} is never bulkheaded — hashing only ever happens once, at client registration or
 * secret rotation, not on the repeated hot path {@code matches} sits on, so there is nothing to
 * protect there.
 */
final class Argon2BulkheadPasswordEncoder implements PasswordEncoder {

  private final Argon2PasswordEncoder delegate =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final CpuBoundVerificationGate gate;

  /* package */ Argon2BulkheadPasswordEncoder(final CpuBoundVerificationGate gate) {
    this.gate = gate;
  }

  @Override
  public String encode(final CharSequence rawPassword) {
    return delegate.encode(rawPassword);
  }

  @Override
  public boolean matches(final CharSequence rawPassword, final String encodedPassword) {
    return gate.runBounded(() -> delegate.matches(rawPassword, encodedPassword))
        .orElseThrow(Argon2BulkheadPasswordEncoder::overloadedException);
  }

  private static OAuth2AuthenticationException overloadedException() {
    return new OAuth2AuthenticationException(
        new OAuth2Error(
            OAuth2ErrorCodes.SERVER_ERROR,
            "Too many concurrent authentication requests — please retry.",
            null));
  }
}
