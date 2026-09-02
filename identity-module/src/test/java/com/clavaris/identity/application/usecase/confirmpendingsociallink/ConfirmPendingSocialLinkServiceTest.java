package com.clavaris.identity.application.usecase.confirmpendingsociallink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PendingSocialLink;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConfirmPendingSocialLinkServiceTest {

  private PendingSocialLinkRepository pendingLinks;
  private SocialIdentityRepository socialIdentities;
  private AccountRepository accounts;
  private EventOutboxWriter outbox;
  private ConfirmPendingSocialLinkService service;

  @BeforeEach
  void setUp() {
    pendingLinks = mock(PendingSocialLinkRepository.class);
    socialIdentities = mock(SocialIdentityRepository.class);
    accounts = mock(AccountRepository.class);
    outbox = mock(EventOutboxWriter.class);
    service = new ConfirmPendingSocialLinkService(pendingLinks, socialIdentities, accounts, outbox);
  }

  @Test
  void consumesAnActivePendingLinkAndCreatesTheSocialIdentity() {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("user@example.com"));
    String rawToken = "a-valid-confirmation-token";
    PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            account.id(),
            SocialProvider.GOOGLE,
            "google-sub-123",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    AccountId accountId = service.handle(new ConfirmPendingSocialLinkCommand(rawToken));

    assertThat(accountId).isEqualTo(account.id());
    assertThat(pendingLink.consumedAt()).isPresent();
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(pendingLinks).save(pendingLink);
    verify(socialIdentities).save(any(SocialIdentity.class));
    verify(accounts).save(account);
    verify(outbox).write(eq("social_identity.linked"), eq(account.id()), any(), any());
  }

  @Test
  void translatesAConstraintViolationOnASecondConcurrentConfirmationIntoInvalidLink() {
    // Code review finding: two separate, still-active pending links for the same
    // (account, provider) can both pass their own isActive() check (e.g. a user retries and gets
    // two valid confirmation emails). The first confirm succeeds; the second must present the
    // same "invalid/expired" outcome as any other rejected confirmation, not an unhandled
    // constraint-violation exception.
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("user@example.com"));
    String rawToken = "a-losing-confirmation-token";
    PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            account.id(),
            SocialProvider.GOOGLE,
            "google-sub-123",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        .when(socialIdentities)
        .save(any());
    // Sonar S5778: the lambda below must contain only the one call actually expected to throw —
    // built here, same as every other test in this class, rather than inline in the lambda where
    // the command constructor itself would also count as a possibly-throwing invocation.
    ConfirmPendingSocialLinkCommand command = new ConfirmPendingSocialLinkCommand(rawToken);

    assertThatExceptionOfType(InvalidPendingSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAnUnknownToken() {
    when(pendingLinks.findByConfirmationTokenHash(any())).thenReturn(Optional.empty());
    ConfirmPendingSocialLinkCommand command = new ConfirmPendingSocialLinkCommand("garbage");

    assertThatExceptionOfType(InvalidPendingSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));

    verify(socialIdentities, never()).save(any());
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnAlreadyConsumedPendingLink() {
    String rawToken = "already-used-token";
    PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            AccountId.newId(),
            SocialProvider.GITHUB,
            "gh-456",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    pendingLink.consume();
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    ConfirmPendingSocialLinkCommand command = new ConfirmPendingSocialLinkCommand(rawToken);

    assertThatExceptionOfType(InvalidPendingSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));

    verify(socialIdentities, never()).save(any());
  }

  @Test
  void rejectsAnExpiredPendingLink() {
    String rawToken = "expired-token";
    PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            AccountId.newId(),
            SocialProvider.GITHUB,
            "gh-789",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().minusSeconds(1));
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    ConfirmPendingSocialLinkCommand command = new ConfirmPendingSocialLinkCommand(rawToken);

    assertThatExceptionOfType(InvalidPendingSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));

    verify(socialIdentities, never()).save(any());
  }
}
