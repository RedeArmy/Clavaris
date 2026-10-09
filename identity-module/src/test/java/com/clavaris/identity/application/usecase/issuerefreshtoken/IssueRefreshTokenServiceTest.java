package com.clavaris.identity.application.usecase.issuerefreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class IssueRefreshTokenServiceTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private SessionRepository sessions;
  private RefreshTokenRepository refreshTokens;
  private SecurityMetricsRecorder metrics;
  private AccountRepository accounts;
  private SessionPolicyProvider sessionPolicyProvider;
  private AccountTokenRevoker accountTokenRevoker;
  private IssueRefreshTokenService service;

  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    sessions = mock(SessionRepository.class);
    refreshTokens = mock(RefreshTokenRepository.class);
    metrics = mock(SecurityMetricsRecorder.class);
    accounts = mock(AccountRepository.class);
    sessionPolicyProvider = mock(SessionPolicyProvider.class);
    accountTokenRevoker = mock(AccountTokenRevoker.class);
    service =
        new IssueRefreshTokenService(
            sessions, refreshTokens, metrics, accounts, sessionPolicyProvider, accountTokenRevoker);

    // Default: every test below exercises an ordinary login (multi-session handling enabled,
    // the default) unless it deliberately overrides these stubs to prove the revocation-cascade
    // path itself.
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));
    when(sessionPolicyProvider.policyFor(organizationId))
        .thenReturn(SessionPolicySnapshot.defaults());

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
    verify(sessions).insert(any());
    verify(refreshTokens).insert(any());
    verify(metrics).increment("clavaris.auth.token.issued", "tokenType", "refresh_token");
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

  // Clerk "Sessions" settings parity.
  @Test
  void revokesEveryPriorSessionWhenMultiSessionHandlingIsDisabled() {
    when(sessionPolicyProvider.policyFor(organizationId))
        .thenReturn(new SessionPolicySnapshot(10_080, 10_080, 10, false));
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

    service.handle(new IssueRefreshTokenCommand(accountId, List.of("openid"), expiresAt));

    verify(refreshTokens).revokeAllActiveForAccount(accountId);
    verify(sessions).revokeAllActiveForAccount(accountId);
    verify(accountTokenRevoker).revokeAllTokensFor(accountId);
    // The new session/token this same call opens must still succeed — the cascade only touches
    // what existed before it.
    verify(sessions).insert(any());
    verify(refreshTokens).insert(any());
  }

  @Test
  void neverRevokesAnythingWhenMultiSessionHandlingIsEnabled() {
    // setUp()'s own default already stubs this, but the assertion is the point of this test, not
    // the stubbing — same "name the invariant explicitly" rationale as the sibling test above.
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

    service.handle(new IssueRefreshTokenCommand(accountId, List.of("openid"), expiresAt));

    verify(refreshTokens, never()).revokeAllActiveForAccount(any());
    verify(sessions, never()).revokeAllActiveForAccount(any());
    verifyNoInteractions(accountTokenRevoker);
  }
}
