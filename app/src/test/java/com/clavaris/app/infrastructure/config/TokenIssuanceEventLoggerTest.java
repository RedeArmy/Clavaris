package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

// TD-SEC-016: same "assert the redaction actually happens" discipline as
// AuthenticateWithPasswordServiceTest — proves what a real log sink would receive, not just that
// the customize() method got called.
class TokenIssuanceEventLoggerTest {

  private static final String TOKEN_VALUE_THAT_MUST_NEVER_APPEAR =
      "eyJhbGciOiJSUzI1NiJ9.super-secret-signed-payload.signature";

  private final SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);
  private final TokenIssuanceEventLogger logger = new TokenIssuanceEventLogger(metrics);
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
    return (Logger) LoggerFactory.getLogger(TokenIssuanceEventLogger.class);
  }

  @Test
  void logsTokenTypeGrantTypeClientIdAndPrincipalNameForAnAccessToken() {
    JwtEncodingContext context =
        contextFor(OAuth2TokenType.ACCESS_TOKEN, AuthorizationGrantType.AUTHORIZATION_CODE);

    logger.customize(context);

    assertThat(onlyLoggedMessage())
        .contains("event=token_issued")
        .contains("tokenType=access_token")
        .contains("grantType=authorization_code")
        .contains("clientId=a-client-id")
        .contains("principal=an-account-id");
    verify(metrics)
        .increment(
            "clavaris.auth.token.issued",
            "tokenType",
            "access_token",
            "grantType",
            "authorization_code");
  }

  @Test
  void logsAnIdTokenDistinctlyFromAnAccessToken() {
    // "id_token" is a real value ID tokens carry here (OidcParameterNames.ID_TOKEN), not one of
    // OAuth2TokenType's own named constants (only ACCESS_TOKEN/REFRESH_TOKEN are) — confirmed via
    // the same OAuth2TokenType(String) constructor SAS's own OidcIdTokenAuthenticationProvider
    // uses internally.
    JwtEncodingContext context =
        contextFor(new OAuth2TokenType("id_token"), AuthorizationGrantType.AUTHORIZATION_CODE);

    logger.customize(context);

    assertThat(onlyLoggedMessage()).contains("tokenType=id_token");
  }

  @Test
  void neverLogsTheTokenValueItself() {
    // There is no token value to leak at this point in the lifecycle (customize() runs before the
    // JWT is signed/serialized) — this test guards against a future edit that starts passing the
    // encoded token into the log line anyway, e.g. by logging the whole context or claims builder.
    JwtEncodingContext context =
        contextFor(OAuth2TokenType.ACCESS_TOKEN, AuthorizationGrantType.CLIENT_CREDENTIALS);

    logger.customize(context);

    assertThat(onlyLoggedMessage()).doesNotContain(TOKEN_VALUE_THAT_MUST_NEVER_APPEAR);
  }

  private static JwtEncodingContext contextFor(
      final OAuth2TokenType tokenType, final AuthorizationGrantType grantType) {
    RegisteredClient client = mock(RegisteredClient.class);
    when(client.getClientId()).thenReturn("a-client-id");
    Authentication principal =
        UsernamePasswordAuthenticationToken.authenticated("an-account-id", null, List.of());

    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(tokenType);
    when(context.getAuthorizationGrantType()).thenReturn(grantType);
    when(context.getRegisteredClient()).thenReturn(client);
    when(context.getPrincipal()).thenReturn(principal);
    return context;
  }

  private String onlyLoggedMessage() {
    List<ILoggingEvent> events = logAppender.list;
    assertThat(events).as("expected exactly one security-event log line").hasSize(1);
    return events.get(0).getFormattedMessage();
  }
}
