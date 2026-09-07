package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.CpuBoundVerificationGate;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;

/**
 * Code review finding (2026-09-01): {@code OrganizationAuthorizationServerConfig} and {@code
 * PlatformAuthorizationServerConfig} each swapped {@code ClientSecretAuthenticationProvider}'s own
 * password encoder for Argon2 via an identical, byte-for-byte {@code .clientAuthentication(...)}
 * block — confirmed by direct comparison, not just line-count. Extracted here so the fix (and its
 * own reasoning) has exactly one place to live, not two copies that could silently drift apart.
 *
 * <p>Both call sites hit the same real gap without this: confirmed live that a bare {@code
 * client_credentials} request otherwise fails with "Given that there is no default password encoder
 * configured, each password must have a password encoding prefix" — SAS's default {@code
 * ClientSecretAuthenticationProvider} uses Spring Security's {@code DelegatingPasswordEncoder},
 * which expects a {@code "{id}"} bracket prefix on stored hashes to route to the right algorithm.
 * {@link Argon2BulkheadPasswordEncoder} (ADR-0005, same one {@code client-registry-module}'s {@code
 * Argon2ClientSecretHasher} already hashes with) produces bare {@code "$argon2id$..."} output with
 * no such prefix — this swaps the provider's own encoder to match what's actually stored, rather
 * than reformatting every stored hash to fit the delegating wrapper's convention.
 *
 * <p>TD-FUT-017: the plain {@code Argon2PasswordEncoder} this used to install directly is now
 * wrapped in {@link Argon2BulkheadPasswordEncoder} — this is the actual client-secret-verification
 * call site `load-testing/README.md` §2 measured as {@code /oauth2/token}'s real concurrency
 * ceiling (that run used the {@code client_credentials} grant specifically, i.e. exactly this code
 * path, not the interactive password-login flow — see that README's own §2 for the measured
 * numbers). {@code gate} is the same shared {@code common} bean {@code identity-module}'s {@code
 * Argon2PasswordVerifier} bulkheads its own password checks through, since both compete for the
 * same limited CPU budget.
 */
final class Argon2ClientAuthenticationSupport {

  private Argon2ClientAuthenticationSupport() {
    // Static helper only — see this class's own Javadoc.
  }

  // Method reference target for OAuth2ClientAuthenticationConfigurer#authenticationProviders
  // (Consumer<List<AuthenticationProvider>>) — both call sites pass this via a small lambda now
  // (not a bare method reference any more) purely to thread `gate` through; the shape at the call
  // site is otherwise unchanged from before TD-FUT-017.
  /* package */ static void useArgon2PasswordEncoder(
      final List<AuthenticationProvider> providers, final CpuBoundVerificationGate gate) {
    final PasswordEncoder bulkheadedEncoder = new Argon2BulkheadPasswordEncoder(gate);
    providers.stream()
        .filter(ClientSecretAuthenticationProvider.class::isInstance)
        .map(ClientSecretAuthenticationProvider.class::cast)
        .forEach(provider -> provider.setPasswordEncoder(bulkheadedEncoder));
  }
}
