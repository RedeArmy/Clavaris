package com.clavaris.app.infrastructure.config;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of the per-tenant session cookies: {@code SESSION_<organizationId without dashes>}.
 *
 * <p>Every tenant used to share the single cookie {@code SESSION} (path {@code /}), so signing in
 * to a second Organization in the same browser reused the first one's {@code HttpSession} and
 * overwrote its {@code SecurityContext}: the first Organization's user was silently signed out. One
 * cookie per Organization makes two Organizations two independent sessions in the browser's jar,
 * the same fix the platform tier already has with {@code PLATFORM_SESSION}.
 *
 * <p>The Organization id is the whole name (not a hash): it is already in every tenant URL, so the
 * name leaks nothing, and a full id can never collide. At roughly 100 bytes a cookie, dozens of
 * Organizations in one browser stay far below any header limit.
 */
// java:S1075: /o/ is the tenant path convention every SecurityFilterChain of this application is
// built on (OrganizationAuthorizationServerConfig), not a deployment setting.
@SuppressWarnings("java:S1075")
final class TenantSessionCookies {

  /**
   * The cookie every tenant used before; still the one flat (organization-less) paths fall back to.
   */
  /* package */ static final String LEGACY_NAME = "SESSION";

  private static final String PREFIX = "SESSION_";
  private static final Pattern TENANT_COOKIE = Pattern.compile("^SESSION_[0-9a-f]{32}$");
  private static final Pattern TENANT_PATH =
      Pattern.compile(
          "^/o/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$");

  private static final String PLATFORM_COOKIE = "PLATFORM_SESSION";
  private static final Pattern TENANT_TAG = Pattern.compile("^(?:platform|[0-9a-f]{32})$");

  private TenantSessionCookies() {}

  /**
   * The cookie a social-login tenant tag names ({@code SocialLoginRedirectController}'s {@code
   * clavaris_tenant}): {@code PLATFORM_SESSION} for the platform tier, {@code SESSION_<id>} for an
   * Organization, empty for anything else (an absent, malformed or forged tag falls back to the
   * legacy cookie in the caller, never to another tenant's).
   */
  /* package */ static Optional<String> nameForTag(final String tag) {
    return Optional.ofNullable(tag)
        .filter(candidate -> TENANT_TAG.matcher(candidate).matches())
        .map(valid -> "platform".equals(valid) ? PLATFORM_COOKIE : PREFIX + valid);
  }

  /** The tag the authorization-request resolver put in front of an OAuth state, if any. */
  /* package */ static Optional<String> tagOfState(final String state) {
    final int separator = state == null ? -1 : state.indexOf('.');
    return separator > 0 ? Optional.of(state.substring(0, separator)) : Optional.empty();
  }

  /* package */ static String nameFor(final UUID organizationId) {
    return PREFIX + organizationId.toString().replace("-", "");
  }

  /** The Organization a tenant cookie name belongs to; empty for any other cookie name. */
  /* package */ static Optional<UUID> organizationOf(final String cookieName) {
    return isTenantCookie(cookieName)
        ? Optional.of(
            UUID.fromString(
                cookieName
                    .substring(PREFIX.length())
                    .replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")))
        : Optional.empty();
  }

  /* package */ static boolean isTenantCookie(final String cookieName) {
    return TENANT_COOKIE.matcher(cookieName).matches();
  }

  /**
   * The cookie of the Organization a {@code /o/{organizationId}/...} path belongs to, if it is one.
   */
  /* package */ static Optional<String> nameForPath(final String path) {
    final Matcher matcher = TENANT_PATH.matcher(path);
    return matcher.matches()
        ? Optional.of(nameFor(UUID.fromString(matcher.group(1))))
        : Optional.empty();
  }
}
