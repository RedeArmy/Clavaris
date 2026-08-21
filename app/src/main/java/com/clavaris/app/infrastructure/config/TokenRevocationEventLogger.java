package com.clavaris.app.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-017 / {@code nfr-quality-attributes.md} §5: logs a structured {@code event=token_revoked}
 * line for every successful {@code /oauth2/revoke} call on either issuer tier — the third and (with
 * TD-SEC-014/TD-SEC-016) final security-event surface the NFR names.
 *
 * <p>Wired as the token revocation endpoint's own {@code revocationResponseHandler}, not a
 * decorated {@code OAuth2AuthorizationService}: confirmed live ({@code javap} against the actually-
 * resolved SAS 7.1.0 jar) that {@code OAuth2TokenRevocationEndpointFilter}'s own default success
 * handler does nothing but {@code response.setStatus(200)} — that is the officially documented,
 * SAS-native extension point for exactly this event, and this class must replicate that exact
 * response behaviour itself, not just add logging on top of it, since setting a custom handler
 * replaces the default entirely rather than running alongside it.
 *
 * <p>Never logs the token itself — {@code OAuth2TokenRevocationAuthenticationToken.getToken()} is
 * deliberately never read here. What IS logged: the token-type hint the client supplied ({@code
 * access_token}/{@code refresh_token}, per RFC 7009 §2.1 — advisory only, not authoritative) and
 * the revoking client's {@code client_id} (a public identifier, not a secret — same reasoning
 * already applied in {@code TokenIssuanceEventLogger}).
 *
 * <p>Per RFC 7009 §2.2, the endpoint returns 200 whether or not the presented token actually
 * existed/was already invalid — this line only means "a client presented a token here," not "a live
 * token was destroyed." Distinguishing those would require inspecting {@code
 * OAuth2AuthorizationService} state before/after, real scope this class deliberately doesn't take
 * on.
 */
@Component
class TokenRevocationEventLogger implements AuthenticationSuccessHandler {

  private static final Logger LOG = LoggerFactory.getLogger(TokenRevocationEventLogger.class);

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ TokenRevocationEventLogger() {
    // Intentionally empty — this class holds no state, only the onAuthenticationSuccess
    // method below.
  }

  @Override
  @SuppressWarnings("PMD.GuardLogStatement") // same false-positive rationale as
  // TokenIssuanceEventLogger: every logged argument is a cheap accessor on an already-built
  // Authentication, not an expensive computation the rule exists to guard against.
  public void onAuthenticationSuccess(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication) {
    if (authentication instanceof OAuth2TokenRevocationAuthenticationToken revocation) {
      LOG.info(
          "event=token_revoked tokenTypeHint={} clientId={}",
          revocation.getTokenTypeHint(),
          clientIdOf(revocation));
    }
    // Replicates OAuth2TokenRevocationEndpointFilter's own default success response exactly
    // (confirmed via javap: it does nothing else) — this handler fully replaces the default,
    // it doesn't run alongside it.
    response.setStatus(HttpStatus.OK.value());
  }

  // Early return for the expected case, falling through to the defensive one below — same
  // "clearer as two exits than one accumulated through nested branching" rationale already used
  // in LoginController/RegisterAccountController.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String clientIdOf(final OAuth2TokenRevocationAuthenticationToken revocation) {
    if (revocation.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientAuth) {
      return clientAuth.getRegisteredClient().getClientId();
    }
    // Defensive fallback only — every client reaching this handler has already authenticated via
    // the standard client-authentication chain, so the branch above is expected to always match
    // in practice; this exists so a future SAS change can't turn into a silent NullPointerException
    // here instead of a slightly-less-precise log line.
    return String.valueOf(revocation.getPrincipal());
  }
}
