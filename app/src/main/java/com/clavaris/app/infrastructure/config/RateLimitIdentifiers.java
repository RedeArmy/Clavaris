package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link RateLimitRule#keyExtractor()} implementations shared across every security chain's own
 * rule list — kept here, not duplicated per chain, since "how do I read the email a login form
 * submitted" doesn't change depending on which tier's login page it was.
 *
 * <p>PMD.OnlyOneReturn suppressed class-wide: every method here follows the same "present/absent/
 * malformed" multi-exit shape already established elsewhere in this codebase (e.g.
 * CurrentPlatformAccountResolverBridge), not an organically grown method that should be
 * restructured.
 */
@SuppressWarnings("PMD.OnlyOneReturn")
final class RateLimitIdentifiers {

  private static final String BASIC_AUTH_PREFIX = "Basic ";

  private RateLimitIdentifiers() {}

  /**
   * The {@code email} form field, normalized the same way {@code Email}'s own domain constructor
   * does (trim + lowercase) — so two requests differing only in case never count against separate
   * counters. Returns {@code null} (skip this rule) if the field is missing or blank; a request
   * with no email to key on isn't a credential-stuffing attempt this layer can meaningfully count.
   */
  /* package */ static String emailFormField(final HttpServletRequest request) {
    final String email = request.getParameter("email");
    if (email == null || email.isBlank()) {
      return null;
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Source IP. {@code request.getRemoteAddr()} directly — no reverse proxy sits in front of
   * Clavaris in the deployment this codebase actually ships today ({@code docker-compose.yml}
   * exposes {@code app} on 8080 directly); honoring {@code X-Forwarded-For} un-guarded would let
   * that same header be spoofed by any direct caller to fake a different source identifier
   * entirely, defeating this layer rather than strengthening it. Revisit if/when a real reverse
   * proxy is introduced (Spring's own {@code ForwardedHeaderFilter}, configured to trust only that
   * proxy's own address, is the standard fix at that point — not before).
   */
  /* package */ static String sourceIp(final HttpServletRequest request) {
    return request.getRemoteAddr();
  }

  /**
   * The presented OAuth2 {@code client_id} — Basic-Auth username first (confidential clients, SAS's
   * own default), falling back to the {@code client_id} form parameter (public-client style) per
   * RFC 6749 §2.3. Returns {@code null} if neither is present.
   */
  /* package */ static String oauthClientId(final HttpServletRequest request) {
    final String basicAuthClientId = clientIdFromBasicAuth(request);
    if (basicAuthClientId != null) {
      return basicAuthClientId;
    }
    final String formClientId = request.getParameter("client_id");
    return (formClientId == null || formClientId.isBlank()) ? null : formClientId;
  }

  private static String clientIdFromBasicAuth(final HttpServletRequest request) {
    final String header = request.getHeader("Authorization");
    if (header == null
        || !header.regionMatches(true, 0, BASIC_AUTH_PREFIX, 0, BASIC_AUTH_PREFIX.length())) {
      return null;
    }
    try {
      final String decoded =
          new String(
              Base64.getDecoder().decode(header.substring(BASIC_AUTH_PREFIX.length())),
              StandardCharsets.UTF_8);
      final int colonIndex = decoded.indexOf(':');
      // A malformed Basic-Auth value (no colon, or not valid base64 — caught below) can't be
      // resolved to a client_id — same "malformed input surfaces as absent" convention this
      // filter's own sibling classes already follow, not an exception mid-filter-chain.
      return colonIndex < 0 ? null : decoded.substring(0, colonIndex);
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * The shared read both {@link #authenticatedPlatformAccountId} and {@link
   * #authenticatedPlatformClientId} are built on — same {@link SecurityContextHolder} lookup either
   * way, only the caller's own principal-type assumption differs. Extracted so the two methods
   * below stay distinct call sites (never confusable with one another, per their own Javadoc)
   * without their implementations being a byte-for-byte duplicate of each other.
   */
  private static String currentAuthenticationName() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return (authentication == null || !authentication.isAuthenticated())
        ? null
        : authentication.getName();
  }

  /**
   * The current session's authenticated {@code PlatformAccountId} — same principal-name source
   * {@code CurrentPlatformAccountResolverBridge} reads, but this filter runs before that bridge
   * would ever be reached (it protects {@code POST /platform/dashboard} itself), so it reads {@link
   * SecurityContextHolder} directly rather than depending on organization-module's own resolver
   * port for one string. Returns {@code null} for an unauthenticated request — {@code
   * PlatformDashboardSecurityConfig}'s own {@code hasAuthority(ROLE_PLATFORM_ACCOUNT)} check
   * already rejects those before this rule's outcome would matter.
   */
  // java:S1172: the unused request parameter is structurally required — this method is used as a
  // RateLimitRule#keyExtractor method reference, and that functional interface's shape is
  // Function<HttpServletRequest, String>, the same as every other extractor in this class.
  @SuppressWarnings("java:S1172")
  /* package */ static String authenticatedPlatformAccountId(final HttpServletRequest request) {
    return currentAuthenticationName();
  }

  /**
   * TD-SEC-030: the presented platform-tier {@code client_id} for an already-authenticated {@code
   * /api/v1/admin/**} call — {@code AdminApiSecurityConfig}'s own resource-server chain sets {@code
   * JwtAuthenticationToken#getName()} to the validated access token's {@code sub} claim, which
   * SAS's own client_credentials issuance (confirmed live, {@code
   * PlatformAuthorizationServerConfig}) populates with the requesting {@code PlatformClient}'s
   * {@code client_id}. Same {@link SecurityContextHolder} read as {@link
   * #authenticatedPlatformAccountId}, but a distinct method: the principal here is a machine
   * client, never a human-owned {@code PlatformAccountId}, and the two must never be confused at a
   * call site. Returns {@code null} for an unauthenticated request — {@code oauth2ResourceServer}'s
   * own rejection already handles those before this rule's outcome would matter.
   */
  @SuppressWarnings("java:S1172")
  /* package */ static String authenticatedPlatformClientId(final HttpServletRequest request) {
    return currentAuthenticationName();
  }

  /**
   * TD-SEC-035: the current session's authenticated tenant {@code AccountId} — same principal-name
   * source {@code CurrentAccountResolverBridge} reads, but this filter runs before that bridge
   * would ever be reached (it protects {@code /o/{organizationId}/account/**} itself), so it reads
   * {@link SecurityContextHolder} directly, same convention {@link #authenticatedPlatformAccountId}
   * already establishes for its own tier. Returns {@code null} for an unauthenticated request —
   * {@code OrganizationAuthorizationServerConfig}'s own {@code hasAuthority(ROLE_ACCOUNT)} check
   * already rejects those before this rule's outcome would matter.
   */
  @SuppressWarnings("java:S1172")
  /* package */ static String authenticatedAccountId(final HttpServletRequest request) {
    return currentAuthenticationName();
  }
}
