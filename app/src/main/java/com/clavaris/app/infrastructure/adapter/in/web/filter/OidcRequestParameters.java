package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.UriUtils;

/**
 * Pure readers of the OIDC authorization request parameters {@link
 * OidcAuthorizationRequestParamsFilter} acts on.
 */
// PMD.OnlyOneReturn: each reader has distinct outcomes, same rationale as the filter these were
// split out of. PMD.LawOfDemeter: request.getSession() and the SecurityContextHolder chain are the
// standard Servlet/Spring Security API shape, same rationale as the sibling filters.
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
final class OidcRequestParameters {

  private static final String ROLE_ACCOUNT = "ROLE_ACCOUNT";

  private OidcRequestParameters() {
    // Static readers, never instantiated.
  }

  // RFC 6749 §4.1.2.1 / OIDC Core §6.1, §6.2: only the authorization code flow without request
  // objects is supported. SAS answers these with an error page (it never reaches a registered
  // redirect_uri); once the redirect_uri is known to be registered, the spec'd error goes there.
  /* package */ static String unsupportedRequestError(final HttpServletRequest request) {
    final String responseType = request.getParameter("response_type");
    if (request.getParameter("request") != null) {
      return "request_not_supported";
    }
    if (request.getParameter("request_uri") != null) {
      return "request_uri_not_supported";
    }
    return responseType != null && !"code".equals(responseType)
        ? "unsupported_response_type"
        : null;
  }

  /* package */ static List<String> promptValues(final String promptParam) {
    return promptParam == null
        ? List.of()
        : Arrays.stream(promptParam.trim().split("\\s+")).filter(p -> !p.isEmpty()).toList();
  }

  /* package */ static Long parseMaxAge(final String value) {
    if (value == null) {
      return null;
    }
    try {
      final long parsed = Long.parseLong(value.trim());
      return parsed < 0 ? null : parsed;
    } catch (final NumberFormatException _) {
      return null;
    }
  }

  /* package */ static Instant latestFactorAt(final Authentication authentication) {
    Instant latest = Instant.EPOCH;
    for (final GrantedAuthority authority : authentication.getAuthorities()) {
      if (authority instanceof FactorGrantedAuthority factor
          && factor.getIssuedAt().isAfter(latest)) {
        latest = factor.getIssuedAt();
      }
    }
    return latest;
  }

  // True when the authentication is older than max_age (0 means always), the point at which a
  // fresh login is wanted; false when no max_age was given.
  /* package */ static boolean exceedsMaxAge(
      final Authentication authentication, final Long maxAge) {
    return maxAge != null
        && (maxAge == 0
            || Duration.between(latestFactorAt(authentication), Instant.now())
                    .compareTo(Duration.ofSeconds(maxAge))
                > 0);
  }

  // A browser states what a request is for in Sec-Fetch-Dest. The actions that change the
  // End-User's
  // state (ending the session, forgetting a consent) are only taken for a page the End-User is
  // actually navigating to, never for an image, script or fetch another site makes with their
  // cookie. A client that sends no such header (not a browser) is treated as navigating.
  /* package */ static boolean isNavigation(final HttpServletRequest request) {
    final String destination = request.getHeader("Sec-Fetch-Dest");
    return destination == null
        || "document".equals(destination)
        || "iframe".equals(destination)
        || "frame".equals(destination);
  }

  // Appends query parameters to an already-registered URI. Only the new values are encoded: the
  // registered URI is exactly what the client sent at registration and must not be encoded twice
  // (a %20 already in it would become %2520). A null value is skipped.
  /* package */ static String withQuery(final String uri, final Map<String, String> parameters) {
    final Map<String, String> present = new LinkedHashMap<>();
    parameters.forEach(
        (name, value) -> {
          if (value != null) {
            present.put(name, value);
          }
        });
    final StringBuilder result = new StringBuilder(uri);
    char separator = uri.indexOf('?') >= 0 ? '&' : '?';
    for (final Map.Entry<String, String> parameter : present.entrySet()) {
      result
          .append(separator)
          .append(parameter.getKey())
          .append('=')
          .append(UriUtils.encodeQueryParam(parameter.getValue(), StandardCharsets.UTF_8));
      separator = '&';
    }
    return result.toString();
  }

  // The signed-in tenant Account of this request, or null: an anonymous visitor or any other kind
  // of
  // principal (a platform operator) is not an End-User of a tenant.
  /* package */ static Authentication signedInAccount() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    final boolean isAccount =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ROLE_ACCOUNT::equals);
    return isAccount ? authentication : null;
  }

  // With no one signed in the End-User gets the login page anyway; noting that this very request
  // asked for a fresh login lets the resumed request pass after that one login instead of asking
  // for a second.
  /* package */ static void rememberLoginRequest(
      final HttpServletRequest request, final List<String> prompts, final Long maxAge) {
    final boolean freshLoginWanted = prompts.contains("login") || Long.valueOf(0).equals(maxAge);
    if (freshLoginWanted) {
      final HttpSession session = request.getSession();
      session.setAttribute(OidcAuthorizationRequestParamsFilter.REAUTH_REQUESTED_AT, Instant.now());
    }
  }
}
