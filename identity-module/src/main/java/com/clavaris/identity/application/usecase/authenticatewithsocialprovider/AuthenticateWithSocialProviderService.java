package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.event.AccountRegisteredEvent;
import com.clavaris.identity.domain.event.SocialIdentityLinkedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.PendingSocialLink;
import com.clavaris.identity.domain.model.SocialIdentity;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import com.clavaris.identity.domain.service.SocialLinkingPolicy;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestration for {@link AuthenticateWithSocialProviderUseCase} — ADR-0020 Decision 1's three-way
 * linking decision:
 *
 * <ol>
 *   <li>An identity already linked to this {@code (provider, providerUserId)} — log in directly, no
 *       writes at all.
 *   <li>No existing identity and no existing {@code Account} for this email in this Organization —
 *       a brand-new signup: create both atomically and log in immediately (no confirmation needed;
 *       there is nothing pre-existing to protect).
 *   <li>No existing identity but an {@code Account} already exists for this email (registered by
 *       password or a different provider) — raise a {@link PendingSocialLink} and email the account
 *       holder's existing address; never log in on this request (BR-ID-09).
 * </ol>
 *
 * <p>Deliberately not one flat {@code @Transactional} method: branch 2's writes are atomic (wrapped
 * in {@link #transactionTemplate}, same split {@code AddWorkspaceMemberService} already
 * establishes) but involve no network call; branch 3's single write is followed by a real mail send
 * that must not hold a database transaction open across it (same non-transactional-mail-send
 * discipline as {@code RequestEmailVerificationService}).
 *
 * <p>Code review finding: {@code
 * authenticateplatformaccountwithsocialprovider.AuthenticatePlatformAccountWithSocialProviderService}
 * (platform tier) duplicates this whole three-way decision almost line-for-line — no shared base
 * exists (distinct aggregate types, {@code Account}/{@code OrganizationId} vs. {@code
 * PlatformAccount}, a deeper unification is a separately-tracked refactor). If you change the
 * linking decision here, check that class too.
 */
// PMD.LongVariable: policyProvider/socialIdentities/pendingLinks/transactionTemplate match their
// own collaborator type names, same convention AddWorkspaceMemberService's own class-level
// suppression already documents. PMD.GuardLogStatement: same false positive
// AuthenticateWithPasswordService's own identical suppression documents — every logged argument
// across all three methods below is a cheap in-memory accessor, not an expensive computation the
// INFO level should be checked before evaluating. PMD.OnlyOneReturn: handle() has three real
// outcomes (two rejections that throw, and a lookup that either returns directly or delegates to
// one of two private methods) — same rationale as SetRateLimitPolicyController's own identical
// suppression for a multi-exit method whose branches are each a real, distinct outcome.
@SuppressWarnings({"PMD.LongVariable", "PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
public class AuthenticateWithSocialProviderService
    implements AuthenticateWithSocialProviderUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuthenticateWithSocialProviderService.class);

  private static final String LOGIN_METRIC = "clavaris.auth.social_login";

  // Extracted purely to remove the "outcome" tag key literal's duplication
  // (PMD.AvoidDuplicateLiterals)
  // across every metrics.increment() call below.
  private static final String OUTCOME_TAG = "outcome";

  // Same reasoning as OUTCOME_TAG above — removes the "provider" tag key literal's own
  // duplication (SonarCloud java:S1192) across every metrics.increment() call below.
  private static final String PROVIDER_TAG = "provider";

  private final AccountRepository accounts;
  private final SocialIdentityRepository socialIdentities;
  private final PendingSocialLinkRepository pendingLinks;
  private final OrganizationSocialLoginPolicyProvider policyProvider;
  private final MailSender mailSender;
  private final EventOutboxWriter outbox;
  private final SecurityMetricsRecorder metrics;
  private final TransactionTemplate transactionTemplate;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // AddWorkspaceMemberService's own identical suppression: this flow genuinely needs every one.
  public AuthenticateWithSocialProviderService(
      final AccountRepository accounts,
      final SocialIdentityRepository socialIdentities,
      final PendingSocialLinkRepository pendingLinks,
      final OrganizationSocialLoginPolicyProvider policyProvider,
      final MailSender mailSender,
      final EventOutboxWriter outbox,
      final SecurityMetricsRecorder metrics,
      final TransactionTemplate transactionTemplate) {
    this.accounts = accounts;
    this.socialIdentities = socialIdentities;
    this.pendingLinks = pendingLinks;
    this.policyProvider = policyProvider;
    this.mailSender = mailSender;
    this.outbox = outbox;
    this.metrics = metrics;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public AuthenticateWithSocialProviderResult handle(
      final AuthenticateWithSocialProviderCommand command) {
    if (!command.emailVerifiedByProvider()) {
      LOG.info(
          "event=social_login_failure organizationId={} provider={} reason=unverified_email",
          command.organizationId(),
          command.provider());
      recordFailure("unverified_email");
      throw new UnverifiedProviderEmailException();
    }

    // ADR-0020 Decision 3, BR-ID-12: re-verified here, at the point of actual use, not trusted from
    // an earlier UI-level gate — see OrganizationSocialLoginPolicyProvider's own Javadoc.
    if (!policyProvider.isProviderAllowed(command.organizationId(), command.provider())) {
      LOG.info(
          "event=social_login_failure organizationId={} provider={} reason=provider_not_allowed",
          command.organizationId(),
          command.provider());
      recordFailure("provider_not_allowed");
      throw new SocialLoginNotAllowedException(command.organizationId(), command.provider());
    }

    // CLAUDE.md §5: scoped by organizationId — a returning login must never resolve an identity
    // that belongs to a different Organization's Account pool (code review finding).
    final Optional<SocialIdentity> existingIdentity =
        socialIdentities.findByOrganizationIdAndProviderAndProviderUserId(
            command.organizationId(), command.provider(), command.providerUserId());
    if (existingIdentity.isPresent()) {
      final SocialIdentity identity = existingIdentity.get();
      LOG.info(
          "event=social_login_success organizationId={} accountId={} provider={} outcome=returning",
          command.organizationId(),
          identity.accountId(),
          command.provider());
      metrics.increment(
          LOGIN_METRIC, PROVIDER_TAG, command.provider().name(), OUTCOME_TAG, "returning");
      return new AuthenticateWithSocialProviderResult.LoggedIn(identity.accountId());
    }

    final Optional<Account> existingAccount =
        accounts.findByOrganizationIdAndEmail(command.organizationId(), command.email());
    if (existingAccount.isEmpty()) {
      return linkBrandNewAccount(command);
    }
    return raisePendingLinkForExistingAccount(command, existingAccount.get());
  }

  private AuthenticateWithSocialProviderResult linkBrandNewAccount(
      final AuthenticateWithSocialProviderCommand command) {
    try {
      return transactionTemplate.execute(
          status -> {
            final Account account = Account.register(command.organizationId(), command.email());
            // The provider already proved control of this email (guarded above) — no reason to
            // make a brand-new social signup go through email verification a second time.
            account.verifyEmail();
            accounts.save(account);

            final SocialIdentity identity =
                SocialIdentity.link(
                    account.id(),
                    command.organizationId(),
                    command.provider(),
                    command.providerUserId());
            socialIdentities.save(identity);

            outbox.write(
                "account.created",
                account.id(),
                command.organizationId(),
                AccountRegisteredEvent.from(account));
            outbox.write(
                "social_identity.linked",
                account.id(),
                command.organizationId(),
                SocialIdentityLinkedEvent.from(identity, command.organizationId()));

            LOG.info(
                "event=social_login_success organizationId={} accountId={} provider={}"
                    + " outcome=new_signup",
                command.organizationId(),
                account.id(),
                command.provider());
            metrics.increment(
                LOGIN_METRIC, PROVIDER_TAG, command.provider().name(), OUTCOME_TAG, "new_signup");

            return new AuthenticateWithSocialProviderResult.LoggedIn(account.id());
          });
    } catch (final DataIntegrityViolationException e) {
      // Code review finding, TOCTOU: handle()'s own existingAccount check and this transaction's
      // own saveAndFlush (ux_accounts_organization_id_email) are not atomic with each other — two
      // concurrent first-time social logins for the same (organizationId, email) but different
      // providers can both observe existingAccount.isEmpty()==true and both race into this
      // method. The transaction above already rolled back cleanly; the account that won the race
      // is now visible, so fall back to the same "account already exists" branch handle() would
      // have taken had it observed it first, instead of letting the loser surface as an unhandled
      // 500.
      final Account winningAccount =
          accounts
              .findByOrganizationIdAndEmail(command.organizationId(), command.email())
              .orElseThrow(() -> e);
      return raisePendingLinkForExistingAccount(command, winningAccount);
    }
  }

  private AuthenticateWithSocialProviderResult raisePendingLinkForExistingAccount(
      final AuthenticateWithSocialProviderCommand command, final Account account) {
    final String rawToken = RefreshTokenSecret.generateRawValue();
    final PendingSocialLink pendingLink =
        PendingSocialLink.raise(
            account.id(),
            command.provider(),
            command.providerUserId(),
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(SocialLinkingPolicy.CONFIRMATION_TOKEN_TTL));
    pendingLinks.save(pendingLink);

    // Outside any transaction — same non-transactional-mail-send discipline as
    // RequestEmailVerificationService, and deliberately sent to the account's OWN email of record
    // (account.email()), never anything the social provider itself reported (BR-ID-09).
    mailSender.sendSocialLinkConfirmation(
        account.email().value(), command.organizationId(), command.provider(), rawToken);

    LOG.info(
        "event=social_login_confirmation_required organizationId={} accountId={} provider={}",
        command.organizationId(),
        account.id(),
        command.provider());
    metrics.increment(
        LOGIN_METRIC,
        PROVIDER_TAG,
        command.provider().name(),
        OUTCOME_TAG,
        "confirmation_required");

    return new AuthenticateWithSocialProviderResult.ConfirmationRequired();
  }

  private void recordFailure(final String reason) {
    metrics.increment(LOGIN_METRIC, OUTCOME_TAG, "failure", "reason", reason);
  }
}
