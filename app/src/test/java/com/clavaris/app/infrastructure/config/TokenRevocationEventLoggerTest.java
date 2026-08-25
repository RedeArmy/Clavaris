package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

// TD-SEC-017: same "assert the redaction actually happens" discipline as
// AuthenticateWithPasswordServiceTest/TokenIssuanceEventLoggerTest — proves what a real log sink
// would receive, and that the endpoint's default 200-OK response is still replicated correctly
// (a custom AuthenticationSuccessHandler replaces SAS's own default, it doesn't run alongside it).
class TokenRevocationEventLoggerTest {

  private static final String TOKEN_VALUE_THAT_MUST_NEVER_APPEAR = "a-real-opaque-token-value";

  private final SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);
  private final TokenRevocationEventLogger logger = new TokenRevocationEventLogger(metrics);
  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    logAppender.start();
    loggerUnderTest().addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    loggerUnderTest().detachAppender(logAppender);
    logAppender.stop();
    logAppender.list.clear();
  }

  private static Logger loggerUnderTest() {
    return (Logger) LoggerFactory.getLogger(TokenRevocationEventLogger.class);
  }

  @Test
  void logsClientIdAndSetsA200ResponseButNeverLogsTheTokenItself() {
    RegisteredClient client = mock(RegisteredClient.class);
    when(client.getClientId()).thenReturn("a-client-id");
    OAuth2ClientAuthenticationToken clientAuth =
        new OAuth2ClientAuthenticationToken(
            client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null);
    OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            TokenType.BEARER,
            TOKEN_VALUE_THAT_MUST_NEVER_APPEAR,
            Instant.now(),
            Instant.now().plusSeconds(3600));
    OAuth2TokenRevocationAuthenticationToken revocation =
        new OAuth2TokenRevocationAuthenticationToken(accessToken, clientAuth);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    logger.onAuthenticationSuccess(request, response, revocation);

    verify(response).setStatus(200);
    List<ILoggingEvent> events = logAppender.list;
    assertThat(events).hasSize(1);
    String message = events.get(0).getFormattedMessage();
    assertThat(message)
        .contains("event=token_revoked")
        .contains("clientId=a-client-id")
        .doesNotContain(TOKEN_VALUE_THAT_MUST_NEVER_APPEAR);
    verify(metrics).increment("clavaris.auth.token.revoked", "tokenTypeHint", "unspecified");
  }

  @Test
  void logsNothingButStillSetsA200ResponseForAnUnrecognisedAuthenticationType() {
    // RFC 7009 §2.2: the endpoint always answers 200, even when the presented token was already
    // invalid/unknown. This class only knows how to describe an
    // OAuth2TokenRevocationAuthenticationToken — anything else reaching onAuthenticationSuccess
    // (not expected in practice, but not this class's job to rule out) must still get its 200,
    // just with nothing logged about it.
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    AnonymousAuthenticationToken unrelatedAuthentication =
        new AnonymousAuthenticationToken(
            "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

    logger.onAuthenticationSuccess(request, response, unrelatedAuthentication);

    verify(response).setStatus(200);
    assertThat(logAppender.list).isEmpty();
  }
}
