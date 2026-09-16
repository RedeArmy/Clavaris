package com.clavaris.identity.infrastructure.adapter.in.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Clerk "customize redirect URLs" parity: a tiny shared helper for appending {@code clientId}/
 * {@code redirectUrl}/{@code email} onto a genuine cross-URL redirect (a multi-step flow's own
 * {@code requestCode}/{@code requestLink}-style hop) — same-URL form resubmissions ({@code
 * th:action="@{''}"}) never need this, the browser already carries the query string forward on its
 * own. Properly URL-encodes the value.
 *
 * <p><b>SDE-III review, 2026-09-15 — real bug found and closed:</b> {@code email} used to be a
 * second, pre-existing gap this class deliberately didn't extend to — {@code
 * EmailCodeSignInController#requestCode}/{@code
 * RegisterAccountController#completePasswordlessSignUp} both concatenated it directly into the
 * redirect target instead of calling {@link #appendIfPresent} the way every other param on the same
 * hop already did. Hibernate Validator's default {@code @Email} pattern permits {@code &} in the
 * local part, so a value like {@code "x&foo=bar@example.com"} passed validation and could inject an
 * extra query parameter into that {@code Location} header (clobbering a later-appended {@code
 * redirectUrl}/{@code clientId}, since Spring's own {@code @RequestParam} binding takes the first
 * occurrence) — a real header/query injection primitive, not merely a style inconsistency. Both
 * call sites now route {@code email} through this same method.
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
