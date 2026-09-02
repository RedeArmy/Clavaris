package com.clavaris.app.infrastructure.config;

import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
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
 * {@link Argon2PasswordEncoder} (ADR-0005, same one {@code client-registry-module}'s {@code
 * Argon2ClientSecretHasher} already hashes with) produces bare {@code "$argon2id$..."} output with
 * no such prefix — this swaps the provider's own encoder to match what's actually stored, rather
 * than reformatting every stored hash to fit the delegating wrapper's convention.
 */
final class Argon2ClientAuthenticationSupport {

  private Argon2ClientAuthenticationSupport() {
    // Static helper only — see this class's own Javadoc.
  }

  // Method reference target for OAuth2ClientAuthenticationConfigurer#authenticationProviders
  // (Consumer<List<AuthenticationProvider>>) — both call sites pass this directly, e.g.
  // `clientAuth -> clientAuth.authenticationProviders(Argon2ClientAuthenticationSupport
  // ::useArgon2PasswordEncoder)`.
  /* package */ static void useArgon2PasswordEncoder(final List<AuthenticationProvider> providers) {
    providers.stream()
        .filter(ClientSecretAuthenticationProvider.class::isInstance)
        .map(ClientSecretAuthenticationProvider.class::cast)
        .forEach(
            provider ->
                provider.setPasswordEncoder(
                    Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
  }
}
