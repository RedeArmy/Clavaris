package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/**
 * Pure readers of the OIDC authorization request parameters {@link
 * OidcAuthorizationRequestParamsFilter} acts on.
 */
// PMD.OnlyOneReturn: each reader has distinct outcomes, same rationale as the filter these were
// split out of.
@SuppressWarnings("PMD.OnlyOneReturn")
final class OidcRequestParameters {

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
}
