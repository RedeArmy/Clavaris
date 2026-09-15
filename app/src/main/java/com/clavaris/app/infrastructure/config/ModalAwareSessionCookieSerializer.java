package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * ADR-0009 §1/§4 / SDE-III review, 2026-09-15 — real bug found and closed: {@link
 * DistributedSessionConfig} previously left the session cookie at Spring Session's own default
 * {@code SameSite=Lax}, including for the {@code display=modal} embedded/iframe login flow {@code
 * ContentSecurityPolicyHeaderWriter}'s own {@code frame-ancestors} relaxation exists to support. A
 * {@code Lax} cookie is not sent by the browser on a cross-site iframe's own requests at all
 * (regardless of GET/POST) — every request inside an embedded login (the GET for the form, the POST
 * submit, the redirect back to {@code /oauth2/authorize}) is "cross-site" from the browser's own
 * perspective whenever the top-level document is a different origin (the embedding consumer's own
 * page) — exactly ADR-0009's whole scenario. The session Spring Security just established therefore
 * never survives past the very next request, producing a silent login loop specifically in the one
 * flow the CSP relaxation exists to support.
 *
 * <p><b>Not a blanket "{@code SameSite=None} everywhere" fix.</b> Browsers drop a {@code
 * SameSite=None} cookie outright unless it is also {@code Secure} — forcing that unconditionally
 * would break every plain-HTTP login, including this project's own integration test suite ({@code
 * AuthorizationCodeFlowIntegrationTest} and siblings all run their embedded Spring Boot Test server
 * over plain HTTP, confirmed live, no TLS), a strictly worse regression than the one being fixed
 * here. Instead: {@code SameSite} stays the Spring Session default ({@code Lax}, via {@link
 * #laxSerializer}) for the overwhelming majority of ordinary, non-embedded requests, and only
 * widens to {@code None} (via {@link #noneSerializer}, which lets {@link DefaultCookieSerializer}'s
 * own adaptive default mirror {@code request.isSecure()} for {@code Secure} — same "correct in a
 * real HTTPS deployment, harmless over plain HTTP" precedent {@code DeviceCookie}'s own identical
 * reasoning already documents) for a request that is genuinely part of a {@code display=modal}
 * flow.
 *
 * <p>Detected the same way {@code ContentSecurityPolicyHeaderWriter} already detects it ({@code
 * display=modal} on the login page), remembered in the {@code HttpSession} for the rest of that
 * same flow via {@link #MODAL_FLOW_SESSION_ATTRIBUTE} — a raw per-request query-param check alone
 * would miss every request after the first GET (the POST login submit, the redirect back to {@code
 * /oauth2/authorize}), none of which carry {@code display=modal} themselves, since {@link
 * #writeCookieValue} is what actually needs the wider {@code SameSite} on each of those, not just
 * the first one. Deliberately a plain "ever seen this session" flag, not state-matched the way
 * {@code ContentSecurityPolicyHeaderWriter}'s own consent-page stash is — that precision exists
 * there to stop a stale flag from relaxing an unrelated later {@code frame-ancestors} render (a
 * real, previously-caught regression); a cookie merely staying a little more permissive than
 * strictly necessary for the rest of one browser's own session carries none of that same framing
 * risk.
 */
final class ModalAwareSessionCookieSerializer implements CookieSerializer {

  private static final String DISPLAY_PARAM = "display";
  private static final String DISPLAY_MODAL = "modal";

  @SuppressWarnings("PMD.LongVariable") // the exact session-attribute-key convention every other
  // stashed-flag in this codebase already follows (e.g. ContentSecurityPolicyHeaderWriter's own
  // MODAL_STATE_SESSION_ATTRIBUTE) — a shortened name would only make this harder to grep for.
  private static final String MODAL_FLOW_SESSION_ATTRIBUTE =
      "clavaris.security.session-cookie.modal-flow";

  private final DefaultCookieSerializer laxSerializer = new DefaultCookieSerializer();
  private final DefaultCookieSerializer noneSerializer = new DefaultCookieSerializer();

  /* package */ ModalAwareSessionCookieSerializer() {
    // Deliberately the only difference between the two — see this class's own Javadoc for why
    // Secure is left at DefaultCookieSerializer's own adaptive default on both, never hardcoded.
    noneSerializer.setSameSite("None");
  }

  @Override
  public void writeCookieValue(final CookieValue cookieValue) {
    if (isModalFlow(cookieValue.getRequest())) {
      noneSerializer.writeCookieValue(cookieValue);
    } else {
      laxSerializer.writeCookieValue(cookieValue);
    }
  }

  // SameSite/Secure are response-only attributes — reading back an already-sent cookie value never
  // depends on either, so this delegates to either inner serializer identically; laxSerializer is
  // an arbitrary, equally-correct choice between the two.
  @Override
  public List<String> readCookieValues(final HttpServletRequest request) {
    return laxSerializer.readCookieValues(request);
  }

  // "display=modal on this exact request" / "the session already saw it earlier this flow" are
  // two independent, equally valid exits — same rationale as every other early-return chain in
  // this codebase (e.g. ContentSecurityPolicyHeaderWriter's own identical suppressions).
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static boolean isModalFlow(final HttpServletRequest request) {
    if (DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      request.getSession(true).setAttribute(MODAL_FLOW_SESSION_ATTRIBUTE, Boolean.TRUE);
      return true;
    }
    final HttpSession session = request.getSession(false);
    return session != null
        && Boolean.TRUE.equals(session.getAttribute(MODAL_FLOW_SESSION_ATTRIBUTE));
  }
}
