package com.clavaris.identity.application.usecase.confirmpendingplatformsociallink;

import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PendingPlatformSocialLinkRepository;
import com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider.PlatformSocialIdentityRepository;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.PendingPlatformSocialLink;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmPendingPlatformSocialLinkUseCase}. Mirrors {@code
 * confirmpendingsociallink.ConfirmPendingSocialLinkService} exactly, minus the outbox write — same
 * "no Organization to notify" reasoning {@code RegisterPlatformAccountService}'s own Javadoc
 * documents; a structured {@code event=} log line is the audit trail instead.
 * {@code @Transactional} end to end, same reasoning as {@code
 * ConfirmPlatformAccountEmailVerificationService}.
 */
public class ConfirmPendingPlatformSocialLinkService
    implements ConfirmPendingPlatformSocialLinkUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConfirmPendingPlatformSocialLinkService.class);

  private final PendingPlatformSocialLinkRepository pendingLinks;
  private final PlatformSocialIdentityRepository socialIdentities;
  private final PlatformAccountRepository accounts;

  public ConfirmPendingPlatformSocialLinkService(
      final PendingPlatformSocialLinkRepository pendingLinks,
      final PlatformSocialIdentityRepository socialIdentities,
      final PlatformAccountRepository accounts) {
    this.pendingLinks = pendingLinks;
    this.socialIdentities = socialIdentities;
    this.accounts = accounts;
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public PlatformAccountId handle(final ConfirmPendingPlatformSocialLinkCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final PendingPlatformSocialLink pendingLink =
        pendingLinks
            .findByConfirmationTokenHash(presentedHash)
            .orElseThrow(InvalidPendingPlatformSocialLinkException::new);

    if (!pendingLink.isActive()) {
      throw new InvalidPendingPlatformSocialLinkException();
    }

    pendingLink.consume();
    pendingLinks.save(pendingLink);

    final PlatformSocialIdentity identity =
        PlatformSocialIdentity.link(
            pendingLink.platformAccountId(), pendingLink.provider(), pendingLink.providerUserId());
    try {
      socialIdentities.save(identity);
    } catch (final DataIntegrityViolationException e) {
      // Code review finding: same race as the tenant-tier sibling's own identical catch — two
      // separate, still-active pending links for the same (platformAccount, provider) can both
      // pass isActive(); the second violates
      // ux_platform_social_identities_platform_account_id_provider. Translate into the same
      // "invalid/expired" outcome ConfirmPlatformSocialLinkController already renders.
      throw new InvalidPendingPlatformSocialLinkException(e);
    }

    // Same "clicking the emailed confirmation link is itself proof of email control" reasoning as
    // the tenant-tier sibling's own identical lookup.
    final PlatformAccount account =
        accounts
            .findById(pendingLink.platformAccountId())
            .orElseThrow(InvalidPendingPlatformSocialLinkException::new);
    account.verifyEmail();
    accounts.save(account);

    LOG.info(
        "event=platform_social_identity_linked platformAccountId={} provider={}",
        account.id(),
        pendingLink.provider());

    return pendingLink.platformAccountId();
  }
}
