package com.clavaris.identity.application.usecase.confirmpendingplatformsociallink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PendingPlatformSocialLinkRepository;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PendingPlatformSocialLink;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConfirmPendingPlatformSocialLinkServiceTest {

  private PendingPlatformSocialLinkRepository pendingLinks;
  private PlatformSocialIdentityRepository socialIdentities;
  private PlatformAccountRepository accounts;
  private ConfirmPendingPlatformSocialLinkService service;

  @BeforeEach
  void setUp() {
    pendingLinks = mock(PendingPlatformSocialLinkRepository.class);
    socialIdentities = mock(PlatformSocialIdentityRepository.class);
    accounts = mock(PlatformAccountRepository.class);
    service = new ConfirmPendingPlatformSocialLinkService(pendingLinks, socialIdentities, accounts);
  }

  @Test
  void consumesAnActivePendingLinkAndCreatesTheSocialIdentity() {
    PlatformAccount account = PlatformAccount.register(new Email("founder@example.com"));
    String rawToken = "a-valid-confirmation-token";
    PendingPlatformSocialLink pendingLink =
        PendingPlatformSocialLink.raise(
            account.id(),
            SocialProvider.GOOGLE,
            "google-sub-123",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    PlatformAccountId platformAccountId =
        service.handle(new ConfirmPendingPlatformSocialLinkCommand(rawToken));

    assertThat(platformAccountId).isEqualTo(account.id());
    assertThat(pendingLink.consumedAt()).isPresent();
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(pendingLinks).save(pendingLink);
    verify(socialIdentities).save(any(PlatformSocialIdentity.class));
    verify(accounts).save(account);
  }

  @Test
  void translatesAConstraintViolationOnASecondConcurrentConfirmationIntoInvalidLink() {
    // Code review finding: same race as the tenant-tier sibling's own identical test — two
    // separate, still-active pending links for the same (platformAccount, provider) can both
    // pass isActive(); the second must present the same "invalid/expired" outcome, not an
    // unhandled constraint-violation exception.
    PlatformAccount account = PlatformAccount.register(new Email("founder@example.com"));
    String rawToken = "a-losing-confirmation-token";
    PendingPlatformSocialLink pendingLink =
        PendingPlatformSocialLink.raise(
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
    ConfirmPendingPlatformSocialLinkCommand command =
        new ConfirmPendingPlatformSocialLinkCommand(rawToken);

    assertThatExceptionOfType(InvalidPendingPlatformSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void rejectsAnUnknownToken() {
    when(pendingLinks.findByConfirmationTokenHash(any())).thenReturn(Optional.empty());
    ConfirmPendingPlatformSocialLinkCommand command =
        new ConfirmPendingPlatformSocialLinkCommand("garbage");

    assertThatExceptionOfType(InvalidPendingPlatformSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));

    verify(socialIdentities, never()).save(any());
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnAlreadyConsumedPendingLink() {
    String rawToken = "already-used-token";
    PendingPlatformSocialLink pendingLink =
        PendingPlatformSocialLink.raise(
            PlatformAccountId.newId(),
            SocialProvider.GITHUB,
            "gh-456",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(3600));
    pendingLink.consume();
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    ConfirmPendingPlatformSocialLinkCommand command =
        new ConfirmPendingPlatformSocialLinkCommand(rawToken);

    assertThatExceptionOfType(InvalidPendingPlatformSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));

    verify(socialIdentities, never()).save(any());
  }

  @Test
  void rejectsAnExpiredPendingLink() {
    String rawToken = "expired-token";
    PendingPlatformSocialLink pendingLink =
        PendingPlatformSocialLink.raise(
            PlatformAccountId.newId(),
            SocialProvider.GITHUB,
            "gh-789",
            RefreshTokenSecret.hash(rawToken),
            Instant.now().minusSeconds(1));
    when(pendingLinks.findByConfirmationTokenHash(RefreshTokenSecret.hash(rawToken)))
        .thenReturn(Optional.of(pendingLink));
    ConfirmPendingPlatformSocialLinkCommand command =
        new ConfirmPendingPlatformSocialLinkCommand(rawToken);

    assertThatExceptionOfType(InvalidPendingPlatformSocialLinkException.class)
        .isThrownBy(() -> service.handle(command));

    verify(socialIdentities, never()).save(any());
  }
}
