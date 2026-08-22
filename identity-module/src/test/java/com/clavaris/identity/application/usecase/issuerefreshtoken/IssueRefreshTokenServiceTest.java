package com.clavaris.identity.application.usecase.issuerefreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class IssueRefreshTokenServiceTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());

  private SessionRepository sessions;
  private RefreshTokenRepository refreshTokens;
  private IssueRefreshTokenService service;

  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    sessions = mock(SessionRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    service = new IssueRefreshTokenService(sessions, refreshTokens);

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
    return (Logger) LoggerFactory.getLogger(IssueRefreshTokenService.class);
  }

  @Test
  void opensASessionAndIssuesItsFirstRefreshToken() {
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    IssueRefreshTokenCommand command =
        new IssueRefreshTokenCommand(accountId, List.of("openid", "profile"), expiresAt);

    IssueRefreshTokenResult result = service.handle(command);

    assertThat(result.rawToken()).isNotBlank();
    assertThat(result.expiresAt()).isEqualTo(expiresAt);
    assertThat(result.sessionId()).isNotNull();
    verify(sessions).save(any());
    verify(refreshTokens).save(any());
  }

  @Test
  void logsIssuanceWithoutEverLoggingTheRawTokenValue() {
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

    IssueRefreshTokenResult result =
        service.handle(new IssueRefreshTokenCommand(accountId, List.of("openid"), expiresAt));

    assertThat(logAppender.list).hasSize(1);
    String message = logAppender.list.get(0).getFormattedMessage();
    assertThat(message)
        .contains("event=token_issued")
        .contains("tokenType=refresh_token")
        .contains(accountId.toString())
        .doesNotContain(result.rawToken());
  }
}
