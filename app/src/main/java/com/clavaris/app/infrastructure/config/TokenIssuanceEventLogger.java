package com.clavaris.app.infrastructure.config;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-016 / {@code nfr-quality-attributes.md} §5: logs a structured {@code event=token_issued}
 * line for every JWT (access token and ID token) either issuer tier generates — the counterpart to
 * {@code AuthenticateWithPasswordService}'s login-event logging (TD-SEC-014), same key=value
 * convention, same discipline about what never appears in it.
 *
 * <p>Wired as an {@link OAuth2TokenCustomizer}, not an authentication-event listener, deliberately:
 * confirmed live ({@code javap} against the actually-resolved SAS 7.1.0 jar, not the stale 1.4.1
 * sources still on the classpath from the original spike) that {@code JwtGenerator.generate()}
 * calls this on every token it builds. That's one guaranteed hook shared by both {@code
 * *AuthorizationServerConfig} classes, rather than depending on Spring Security's own {@code
 * AuthenticationEventPublisher} wiring reaching whichever internal {@code AuthenticationManager}
 * each token-grant flow actually uses — unverified, and not what SAS's own extension points are
 * documented for.
 *
 * <p>Never logs the token itself — this runs before the JWT is signed/serialized, so there is no
 * token value in scope to accidentally log in the first place, not just a discipline being followed
 * after the fact. What IS logged: token type (access/ID), grant type, the {@code client_id} (a
 * public identifier, not a secret — same reasoning {@code organizationId}/{@code accountId} are
 * safe in {@code AuthenticateWithPasswordService}'s own logs), and the authenticated principal's
 * name — for a real login that's the {@code accountId} (see {@link
 * SpringSecurityAuthenticatedSessionEstablisher}), for {@code client_credentials} it's the client
 * itself, since there is no separate end-user principal in that grant.
 */
@Component
class TokenIssuanceEventLogger implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private static final Logger LOG = LoggerFactory.getLogger(TokenIssuanceEventLogger.class);

  private final SecurityMetricsRecorder metrics;

  // Constructed only by Spring's own component scan.
  /* package */ TokenIssuanceEventLogger(final SecurityMetricsRecorder metrics) {
    this.metrics = metrics;
  }

  @Override
  // PMD.GuardLogStatement: every argument below is a cheap accessor on an already-built
  // context/token, not an expensive computation the rule exists to guard against.
  // PMD.LawOfDemeter: JwtEncodingContext is a flat, purpose-built accessor facade (the same shape
  // as a DTO/builder), not an object graph this rule's "don't reach through collaborators" concern
  // applies to — extracting a handful of independent fields from it is the API's intended use, not
  // a coupling smell.
  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.LawOfDemeter"})
  public void customize(final JwtEncodingContext context) {
    final Authentication principal = context.getPrincipal();
    final String tokenType = context.getTokenType().getValue();
    LOG.info(
        "event=token_issued tokenType={} grantType={} clientId={} principal={}",
        tokenType,
        context.getAuthorizationGrantType().getValue(),
        context.getRegisteredClient().getClientId(),
        principal.getName());
    metrics.increment(
        "clavaris.auth.token.issued",
        "tokenType",
        tokenType,
        "grantType",
        context.getAuthorizationGrantType().getValue());
  }
}
