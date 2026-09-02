package com.clavaris.identity.application.usecase.confirmpendingsociallink;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.SocialIdentityLinkedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.PendingSocialLink;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmPendingSocialLinkUseCase} — ADR-0020 Decision 1, BR-ID-09: the
 * confirmation half of {@code AuthenticateWithSocialProviderService}'s branch 3.
 * {@code @Transactional} end to end, same reasoning as {@code ConfirmEmailVerificationService}:
 * every step here is an internal database write, no third-party network call, so there's no reason
 * to split the transaction the way the request side does.
 *
 * <p>Deliberately does not need an {@code OrganizationSocialLoginPolicyProvider} re-check the way
 * the request side does — a tenant disabling a provider after a {@link PendingSocialLink} was
 * already raised does not retroactively invalidate a confirmation the account holder themselves is
 * completing by clicking a link only they received; the risky moment (an attacker initiating a
 * disallowed provider's login flow at all) was already blocked upstream.
 */
public class ConfirmPendingSocialLinkService implements ConfirmPendingSocialLinkUseCase {

  private final PendingSocialLinkRepository pendingLinks;
  private final SocialIdentityRepository socialIdentities;
  private final AccountRepository accounts;
  private final EventOutboxWriter outbox;

  public ConfirmPendingSocialLinkService(
      final PendingSocialLinkRepository pendingLinks,
      final SocialIdentityRepository socialIdentities,
      final AccountRepository accounts,
      final EventOutboxWriter outbox) {
    this.pendingLinks = pendingLinks;
    this.socialIdentities = socialIdentities;
    this.accounts = accounts;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public AccountId handle(final ConfirmPendingSocialLinkCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final PendingSocialLink pendingLink =
        pendingLinks
            .findByConfirmationTokenHash(presentedHash)
            .orElseThrow(InvalidPendingSocialLinkException::new);

    if (!pendingLink.isActive()) {
      throw new InvalidPendingSocialLinkException();
    }

    pendingLink.consume();
    pendingLinks.save(pendingLink);

    // PendingSocialLink itself carries no organizationId (it's scoped to an AccountId, which
    // already implies one) — this lookup is also how the confirmation click doubles as proof of
    // email control, same as ConfirmEmailVerificationService's own verifyEmail() call: only the
    // account holder could have received and clicked this link, so an unverified email is now
    // provably verified too. Looked up here (before building the SocialIdentity below) so the new
    // identity row can carry the account's own organizationId (CLAUDE.md §5) rather than being
    // left unscoped the way the pre-review version of this flow was.
    final Account account =
        accounts
            .findById(pendingLink.accountId())
            .orElseThrow(InvalidPendingSocialLinkException::new);
    account.verifyEmail();
    accounts.save(account);

    final SocialIdentity identity =
        SocialIdentity.link(
            pendingLink.accountId(),
            account.organizationId(),
            pendingLink.provider(),
            pendingLink.providerUserId());
    try {
      socialIdentities.save(identity);
    } catch (final DataIntegrityViolationException e) {
      // Code review finding: two separate, still-active pending links for the same
      // (account, provider) can both pass their own isActive() check (e.g. a user retries and
      // gets two valid confirmation emails, or a link-prescanner auto-visits both) — the first
      // confirm succeeds, the second violates ux_social_identities_account_id_provider. Translate
      // into the same "invalid/expired" outcome ConfirmSocialLinkController already renders,
      // instead of letting the raw constraint violation surface as an unhandled 500 (this
      // Thymeleaf controller has no GlobalExceptionHandler coverage — that's @RestController-only).
      throw new InvalidPendingSocialLinkException(e);
    }

    outbox.write(
        "social_identity.linked",
        pendingLink.accountId(),
        account.organizationId(),
        SocialIdentityLinkedEvent.from(identity, account.organizationId()));

    return pendingLink.accountId();
  }
}
