package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.recordsessionoidcid.RecordSessionOidcIdUseCase;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * OIDC Core 12.2: an ID token issued by a refresh must repeat the original's {@code sid}. Spring
 * Authorization Server puts that value on the first ID token only (hashed from the browser's {@code
 * HttpSession}, which the refresh grant never sees), and compares it with the hint's {@code sid} at
 * logout. This copies it onto the Clavaris Session opened for the same code exchange, from where
 * {@link RefreshTokenRotationAuthenticationProvider} repeats it.
 *
 * <p>The code exchange generates the refresh token (opening the Session) before the ID token, and
 * the two meet through a request attribute: {@link SessionBackedRefreshTokenGenerator} leaves the
 * new Session's id, this reads it. Recording is best-effort: if it fails the token is still issued,
 * and a refreshed ID token then carries the Session id as it always did.
 */
// PMD.LongVariable: names say what they hold, same convention as the sibling security classes.
// PMD.LawOfDemeter: context.getClaims()/getTokenType() is the standard SAS API shape.
// PMD.AvoidCatchingGenericException: recording is best-effort by design (see the class Javadoc), so
// any failure of it, whatever its type, must not stop a token being issued.
@SuppressWarnings({"PMD.AvoidCatchingGenericException"})
public final class OidcSessionIdRecorder implements OAuth2TokenCustomizer<JwtEncodingContext> {

  /** Request attribute carrying the id of the Session opened for the current code exchange. */
  /* package */ static final String SESSION_ID_ATTRIBUTE =
      OidcSessionIdRecorder.class.getName() + ".SESSION_ID";

  private static final Logger LOG = LoggerFactory.getLogger(OidcSessionIdRecorder.class);
  private static final String SID_CLAIM = "sid";

  private final RecordSessionOidcIdUseCase recordSessionOidcId;

  public OidcSessionIdRecorder(final RecordSessionOidcIdUseCase recordSessionOidcId) {
    this.recordSessionOidcId = recordSessionOidcId;
  }

  /** Called by the refresh token generator once it has opened the Session. */
  /* package */ static void rememberSession(final UUID sessionId) {
    final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.setAttribute(SESSION_ID_ATTRIBUTE, sessionId, RequestAttributes.SCOPE_REQUEST);
    }
  }

  @Override
  public void customize(final JwtEncodingContext context) {
    if (!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())
        || !AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
      return;
    }
    final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    final Object sessionId =
        attributes == null
            ? null
            : attributes.getAttribute(SESSION_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    final AtomicReference<Object> sid = new AtomicReference<>();
    context.getClaims().claims(claims -> sid.set(claims.get(SID_CLAIM)));
    if (sessionId instanceof UUID openedSession && sid.get() instanceof String value) {
      try {
        recordSessionOidcId.handle(openedSession, value);
      } catch (final RuntimeException failure) {
        LOG.warn("event=session_oidc_sid_not_recorded sessionId={}", openedSession, failure);
      }
    }
  }
}
