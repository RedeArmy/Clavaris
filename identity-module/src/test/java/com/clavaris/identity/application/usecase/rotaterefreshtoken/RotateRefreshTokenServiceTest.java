package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.RefreshTokenReuseDetectedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.RefreshToken;
import com.clavaris.identity.domain.model.Session;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RotateRefreshTokenServiceTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private RefreshTokenRepository refreshTokens;
  private SessionRepository sessions;
  private AccountRepository accounts;
  private AccountTokenRevoker accountTokenRevoker;
  private EventOutboxWriter outbox;
  private RotateRefreshTokenService service;

  private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    refreshTokens = mock(RefreshTokenRepository.class);
    sessions = mock(SessionRepository.class);
    accounts = mock(AccountRepository.class);
    accountTokenRevoker = mock(AccountTokenRevoker.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new RotateRefreshTokenService(
            refreshTokens, sessions, accounts, accountTokenRevoker, outbox);

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
    return (Logger) LoggerFactory.getLogger(RotateRefreshTokenService.class);
  }

  private Session activeSession() {
    return Session.open(accountId, List.of("openid", "profile"));
  }

  private RefreshToken activeTokenFor(final Session session, final String rawValue) {
    return RefreshToken.issue(
        session.id(),
        accountId,
        RefreshTokenSecret.hash(rawValue),
        Instant.now().plusSeconds(3600));
  }

  private static RotateRefreshTokenCommand commandFor(
      final String rawValue, final Instant newExpiresAt) {
    return new RotateRefreshTokenCommand(rawValue, List.of(), newExpiresAt);
  }

  @Test
  void rotatesAnActiveTokenIntoANewOneAndRevokesTheOld() {
    Session session = activeSession();
    String rawValue = "a-valid-refresh-token-value";
    RefreshToken active = activeTokenFor(session, rawValue);
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash(rawValue)))
        .thenReturn(Optional.of(active));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));
    Instant newExpiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

    RotateRefreshTokenResult result = service.handle(commandFor(rawValue, newExpiresAt));

    assertThat(result.accountId()).isEqualTo(accountId);
    assertThat(result.sessionId()).isEqualTo(session.id());
    assertThat(result.authorizedScopes()).containsExactly("openid", "profile");
    assertThat(result.newRawToken()).isNotBlank().isNotEqualTo(rawValue);
    assertThat(result.newExpiresAt()).isEqualTo(newExpiresAt);
    assertThat(active.isRevoked()).isTrue();
    verify(refreshTokens, times(2)).save(any()); // once for the revoked old, once for the new
    verify(accountTokenRevoker, never()).revokeAllTokensFor(any());
    verify(outbox, never()).write(any(), any(), any());
  }

  @Test
  void rejectsAnUnknownToken() {
    when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());
    RotateRefreshTokenCommand command = commandFor("garbage", Instant.now().plusSeconds(3600));

    assertThatExceptionOfType(InvalidRefreshTokenException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accountTokenRevoker, never()).revokeAllTokensFor(any());
  }

  @Test
  void rejectsANaturallyExpiredTokenWithoutTreatingItAsReuse() {
    Session session = activeSession();
    RefreshToken expired =
        RefreshToken.issue(
            session.id(),
            accountId,
            RefreshTokenSecret.hash("expired-value"),
            Instant.now().minusSeconds(1));
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("expired-value")))
        .thenReturn(Optional.of(expired));
    RotateRefreshTokenCommand command =
        commandFor("expired-value", Instant.now().plusSeconds(3600));

    assertThatExceptionOfType(InvalidRefreshTokenException.class)
        .isThrownBy(() -> service.handle(command));

    // Ordinary expiry is not the BR-ID-03 reuse signal — no mass revocation, no alert.
    verify(accountTokenRevoker, never()).revokeAllTokensFor(any());
    verify(sessions, never()).revokeAllActiveForAccount(any());
    verify(outbox, never()).write(any(), any(), any());
  }

  @Test
  void detectsReuseOfAnAlreadyRotatedTokenAndRevokesEveryActiveTokenForTheAccount() {
    Session session = activeSession();
    RefreshToken alreadyRotatedAway =
        RefreshToken.issue(
            session.id(),
            accountId,
            RefreshTokenSecret.hash("stolen-old-value"),
            Instant.now().plusSeconds(3600));
    alreadyRotatedAway.revoke(); // simulates a prior successful rotation having already happened
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("stolen-old-value")))
        .thenReturn(Optional.of(alreadyRotatedAway));
    when(accounts.findById(accountId))
        .thenReturn(Optional.of(Account.register(organizationId, new Email("victim@example.com"))));
    RotateRefreshTokenCommand command =
        commandFor("stolen-old-value", Instant.now().plusSeconds(3600));

    assertThatExceptionOfType(RefreshTokenReuseDetectedException.class)
        .isThrownBy(() -> service.handle(command));

    // BR-ID-03: every active token for the account, not just the reused one — completed
    // synchronously, before the exception was even thrown. Note: a plain Mockito unit test can
    // only prove these calls happened, not that they actually *commit* — the transactional
    // rollback bug this exact behavior once had (noRollbackFor, see the service's own comment)
    // only a real integration test with a real transaction manager could catch, and did
    // (RefreshTokenRotationIntegrationTest).
    verify(refreshTokens).revokeAllActiveForAccount(accountId);
    verify(sessions).revokeAllActiveForAccount(accountId);
    verify(accountTokenRevoker).revokeAllTokensFor(accountId);
    verify(outbox)
        .write(
            eq("refresh_token.reuse_detected"),
            eq(accountId),
            any(RefreshTokenReuseDetectedEvent.class));
  }

  @Test
  void rejectsARequestedScopeExceedingWhatWasOriginallyAuthorizedWithoutConsumingTheToken() {
    // RFC 6749 §6 — and a real bug this test guards against: this check must run before any
    // mutation, or a rejected over-scoped request would silently burn the presented token anyway
    // (RequestedScopeExceedsAuthorizedScopeException's own Javadoc has the full story).
    Session session = activeSession(); // authorized: openid, profile
    String rawValue = "a-valid-refresh-token-value";
    RefreshToken active = activeTokenFor(session, rawValue);
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash(rawValue)))
        .thenReturn(Optional.of(active));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));
    RotateRefreshTokenCommand command =
        new RotateRefreshTokenCommand(
            rawValue, List.of("openid", "admin"), Instant.now().plusSeconds(3600));

    assertThatExceptionOfType(RequestedScopeExceedsAuthorizedScopeException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(active.isRevoked())
        .as("a rejected over-scoped request must leave the presented token fully usable")
        .isFalse();
    verify(refreshTokens, never()).save(any());
    verify(sessions, never()).save(any());
  }

  @Test
  void aRequestedScopeThatIsASubsetOfWhatWasAuthorizedNarrowsTheGrantedScope() {
    Session session = activeSession(); // authorized: openid, profile
    String rawValue = "a-valid-refresh-token-value";
    RefreshToken active = activeTokenFor(session, rawValue);
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash(rawValue)))
        .thenReturn(Optional.of(active));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));
    RotateRefreshTokenCommand command =
        new RotateRefreshTokenCommand(rawValue, List.of("openid"), Instant.now().plusSeconds(3600));

    RotateRefreshTokenResult result = service.handle(command);

    assertThat(result.authorizedScopes()).containsExactly("openid");
  }

  @Test
  void neverLogsTheRawTokenValueOrItsHashOnAnyPath() {
    Session session = activeSession();
    String rawValue = "a-valid-refresh-token-value";
    RefreshToken active = activeTokenFor(session, rawValue);
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash(rawValue)))
        .thenReturn(Optional.of(active));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));

    RotateRefreshTokenResult result =
        service.handle(commandFor(rawValue, Instant.now().plusSeconds(3600)));

    assertThat(logAppender.list).isNotEmpty();
    for (ILoggingEvent event : logAppender.list) {
      assertThat(event.getFormattedMessage())
          .doesNotContain(rawValue)
          .doesNotContain(result.newRawToken())
          .doesNotContain(RefreshTokenSecret.hash(rawValue))
          .doesNotContain(RefreshTokenSecret.hash(result.newRawToken()));
    }
  }
}
