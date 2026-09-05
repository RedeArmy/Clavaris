package com.clavaris.identity.infrastructure.adapter.in.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Clerk "customize redirect URLs" parity: a tiny shared helper for appending {@code clientId}/
 * {@code redirectUrl} onto a genuine cross-URL redirect (a multi-step flow's own {@code
 * requestCode}/{@code requestLink}-style hop) — same-URL form resubmissions ({@code
 * th:action="@{''}"}) never need this, the browser already carries the query string forward on its
 * own. Properly URL-encodes the value, unlike this package's own pre-existing {@code email=}
 * query-param concatenation (a latent gap this class deliberately doesn't extend to, out of scope
 * for this change).
 */
final class RedirectQueryParams {

  private RedirectQueryParams() {
    // Static utility — not instantiable.
  }

  // Two genuinely distinct outcomes (nothing to append / append one param) — same "each outcome
  // needs its own exit" rationale as DeviceCookie's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static String appendIfPresent(
      final String baseUrl, final String paramName, final String value) {
    if (value == null) {
      return baseUrl;
    }
    final String separator = baseUrl.indexOf('?') >= 0 ? "&" : "?";
    return baseUrl + separator + paramName + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
