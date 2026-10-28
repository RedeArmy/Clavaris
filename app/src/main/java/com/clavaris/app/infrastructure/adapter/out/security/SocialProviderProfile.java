package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Locale;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * What the social sign-in success handler reads off a provider's {@link OAuth2User} (TD-ARCH-042):
 * which {@link SocialProvider} the registration is, the email that provider vouches for, and the
 * profile fields it offers. Pure functions of the principal, split out of {@link
 * SocialLoginAuthenticationSuccessHandler} so that class keeps only the sign-in orchestration.
 */
final class SocialProviderProfile {

  private SocialProviderProfile() {}

  // ADR-0020 Decision 5 plans a future Microsoft provider (and any registration id's own casing
  // could in principle drift from its SocialProvider enum constant) — guards against
  // SocialProvider.valueOf crashing this handler with an uncaught IllegalArgumentException
  // instead of the same clean error redirect every other unresolvable-login case here already
  // gets (code review finding).
  /* package */ static SocialProvider resolveProvider(final String registrationId) {
    try {
      return SocialProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  // ADR-0020 Decision 1: Google's own OIDC email_verified claim is trustworthy directly (Spring
  // Boot's own CommonOAuth2Provider.GOOGLE preset always requests the email scope, and Google
  // always sets this claim when it does); GitHub carries no such claim on its base profile at
  // all — GitHubVerifiedEmailUserService's own synthetic attribute is the only trustworthy source,
  // see its own Javadoc for why the base /user response's own email field is never used directly.
  /* package */ static String resolveVerifiedEmail(
      final SocialProvider provider, final OAuth2User principal) {
    if (provider == SocialProvider.GOOGLE) {
      // Google always authenticates through Spring's own default OidcUserService
      // (SocialLoginConfig only overrides the userService() slot for the non-OIDC GitHub
      // registration), so principal is always a real OidcUser here. Its own typed
      // ClaimAccessor#getClaimAsBoolean is used instead of a raw attribute-map
      // Boolean.TRUE.equals(...) check (code review finding) — the raw check silently rejects a
      // genuinely verified account if Google ever serializes email_verified as the JSON string
      // "true" rather than a native boolean; the typed accessor tolerates either form.
      final OidcUser oidcUser = (OidcUser) principal;
      final String email = oidcUser.getClaimAsString(StandardClaimNames.EMAIL);
      return email != null
              && Boolean.TRUE.equals(oidcUser.getClaimAsBoolean(StandardClaimNames.EMAIL_VERIFIED))
          ? email
          : null;
    }
    final Object verifiedEmail =
        principal.getAttributes().get(GitHubVerifiedEmailUserService.VERIFIED_EMAIL_ATTRIBUTE);
    return verifiedEmail == null ? null : verifiedEmail.toString();
  }

  // ADR-0026: captured for AuthenticateWithSocialProviderCommand's own use — applied only on a
  // brand-new-account signup (AuthenticateWithSocialProviderService's own
  // applySocialProviderProfile/applySocialProviderUsernameIfAvailable), never on a returning
  // login, regardless of what this method returns on every call. Google offers no natural
  // "username" equivalent (unlike GitHub's own stable, already-unique login) — deliberately never
  // fabricated from an email local-part or similar guess, left null instead.
  /* package */ static SocialProfile resolveSocialProfile(
      final SocialProvider provider, final OAuth2User principal) {
    if (provider == SocialProvider.GOOGLE) {
      final OidcUser oidcUser = (OidcUser) principal;
      return new SocialProfile(
          oidcUser.getClaimAsString(StandardClaimNames.GIVEN_NAME),
          oidcUser.getClaimAsString(StandardClaimNames.FAMILY_NAME),
          null,
          oidcUser.getClaimAsString(StandardClaimNames.PICTURE));
    }
    final Object name = principal.getAttributes().get("name");
    final Object login = principal.getAttributes().get("login");
    final Object avatarUrl = principal.getAttributes().get("avatar_url");
    final String[] splitName = splitGitHubDisplayName(name == null ? null : name.toString());
    return new SocialProfile(
        splitName[0],
        splitName[1],
        login == null ? null : login.toString(),
        avatarUrl == null ? null : avatarUrl.toString());
  }

  // GitHub's base /user response carries a single free-text "name" field (often absent entirely —
  // it's an optional profile field on GitHub's own side), never separate given/family name claims
  // the way Google's OIDC profile scope does — a best-effort split on the first space is the only
  // option available, not a real name-parsing guarantee (a one-word name becomes firstName only,
  // lastName stays null).
  private static String[] splitGitHubDisplayName(final String name) {
    if (name == null || name.isBlank()) {
      return new String[] {null, null};
    }
    final String trimmed = name.strip();
    final int firstSpace = trimmed.indexOf(' ');
    if (firstSpace < 0) {
      return new String[] {trimmed, null};
    }
    return new String[] {
      trimmed.substring(0, firstSpace), trimmed.substring(firstSpace + 1).strip()
    };
  }

  /**
   * ADR-0026: the four provider-sourced profile fields {@code
   * AuthenticateWithSocialProviderCommand} carries.
   */
  /* package */ record SocialProfile(
      String firstName, String lastName, String username, String pictureUrl) {}
}
