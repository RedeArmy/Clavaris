package com.clavaris.app.infrastructure.adapter.out.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.recordsessionoidcid.RecordSessionOidcIdUseCase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * OIDC Core 12.2: the sid of the first ID token is copied onto the Session of its code exchange.
 */
class OidcSessionIdRecorderTest {

  private final RecordSessionOidcIdUseCase record = mock(RecordSessionOidcIdUseCase.class);
  private final OidcSessionIdRecorder recorder = new OidcSessionIdRecorder(record);

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static JwtEncodingContext idTokenContext(
      final AuthorizationGrantType grant, final String sid) {
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(new OAuth2TokenType("id_token"));
    when(context.getAuthorizationGrantType()).thenReturn(grant);
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder().claim("sub", "x");
    if (sid != null) {
      claims.claim("sid", sid);
    }
    when(context.getClaims()).thenReturn(claims);
    return context;
  }

  private static UUID aSessionOpenedByThisRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    UUID sessionId = UUID.randomUUID();
    OidcSessionIdRecorder.rememberSession(sessionId);
    return sessionId;
  }

  @Test
  void recordsTheSidOfTheIdTokenOnTheSessionTheSameExchangeOpened() {
    UUID sessionId = aSessionOpenedByThisRequest();

    recorder.customize(idTokenContext(AuthorizationGrantType.AUTHORIZATION_CODE, "the-sid"));

    verify(record).handle(sessionId, "the-sid");
  }

  @Test
  void ignoresAnIdTokenWithoutASid() {
    aSessionOpenedByThisRequest();

    recorder.customize(idTokenContext(AuthorizationGrantType.AUTHORIZATION_CODE, null));

    verifyNoInteractions(record);
  }

  @Test
  void ignoresTheIdTokenOfARefreshGrant() {
    aSessionOpenedByThisRequest();

    recorder.customize(idTokenContext(AuthorizationGrantType.REFRESH_TOKEN, "the-sid"));

    verifyNoInteractions(record);
  }

  @Test
  void ignoresAnExchangeThatOpenedNoSession() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));

    recorder.customize(idTokenContext(AuthorizationGrantType.AUTHORIZATION_CODE, "the-sid"));

    verifyNoInteractions(record);
  }

  @Test
  void aFailureToRecordNeverBreaksTokenIssuance() {
    aSessionOpenedByThisRequest();
    doThrow(new IllegalStateException("db down")).when(record).handle(any(), any());

    recorder.customize(idTokenContext(AuthorizationGrantType.AUTHORIZATION_CODE, "the-sid"));

    verify(record).handle(any(), any());
  }
}
