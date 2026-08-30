package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.PendingPlatformSocialLink;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformSocialIdentity;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import com.clavaris.identity.domain.service.SocialLinkingPolicy;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestration for {@link AuthenticatePlatformAccountWithSocialProviderUseCase}. Mirrors {@code
 * authenticatewithsocialprovider.AuthenticateWithSocialProviderService}'s exact three-way linking
 * decision (ADR-0020 Decision 1), minus two things {@code RegisterPlatformAccountService}'s own
 * Javadoc already explains don't apply at this tier: no {@code
 * OrganizationSocialLoginPolicyProvider} re-check (ADR-0020 Decision 2 — always on, not
 * tenant-configurable) and no outbox write (a {@code PlatformAccount} belongs to no Organization,
 * so there's no webhook consumer to notify — a structured {@code event=} log line is the audit
 * trail instead).
 *
 * <p>Code review finding: that "mirrors" relationship is almost line-for-line duplication, not
 * shared code — no common base exists (distinct aggregate types, {@code PlatformAccount} vs. {@code
 * Account}/{@code OrganizationId}, a deeper unification is a separately-tracked refactor, not
 * something to rush into security-critical auth code without matching test coverage). If you change
 * the linking decision here, check the tenant-tier sibling too.
 */
// PMD.LongVariable/GuardLogStatement/OnlyOneReturn: same rationale as the tenant-tier sibling's
// own identical class-level suppression.
@SuppressWarnings({"PMD.LongVariable", "PMD.GuardLogStatement", "PMD.OnlyOneReturn"})
public class AuthenticatePlatformAccountWithSocialProviderService
    implements AuthenticatePlatformAccountWithSocialProviderUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuthenticatePlatformAccountWithSocialProviderService.class);

  private static final String LOGIN_METRIC = "clavaris.auth.login";
  private static final String OUTCOME_TAG = "outcome";

  // Extracted purely to remove the "tier"/"platform" literals' duplication
  // (PMD.AvoidDuplicateLiterals)
  // across every metrics.increment() call below — every outcome in this class is platform-tier.
  private static final String TIER_TAG = "tier";
  private static final String PLATFORM_TIER = "platform";

  private final PlatformAccountRepository accounts;
  private final PlatformSocialIdentityRepository socialIdentities;
  private final PendingPlatformSocialLinkRepository pendingLinks;
  private final PlatformMailSender mailSender;
  private final SecurityMetricsRecorder metrics;
  private final TransactionTemplate transactionTemplate;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as the
  // tenant-tier sibling's own identical suppression.
  public AuthenticatePlatformAccountWithSocialProviderService(
      final PlatformAccountRepository accounts,
      final PlatformSocialIdentityRepository socialIdentities,
      final PendingPlatformSocialLinkRepository pendingLinks,
      final PlatformMailSender mailSender,
      final SecurityMetricsRecorder metrics,
      final TransactionTemplate transactionTemplate) {
    this.accounts = accounts;
    this.socialIdentities = socialIdentities;
    this.pendingLinks = pendingLinks;
    this.mailSender = mailSender;
    this.metrics = metrics;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public AuthenticatePlatformAccountWithSocialProviderResult handle(
      final AuthenticatePlatformAccountWithSocialProviderCommand command) {
    if (!command.emailVerifiedByProvider()) {
      LOG.info(
          "event=platform_login_failure provider={} reason=unverified_email", command.provider());
      recordFailure("unverified_email");
      throw new UnverifiedPlatformProviderEmailException();
    }

    final Optional<PlatformSocialIdentity> existingIdentity =
        socialIdentities.findByProviderAndProviderUserId(
            command.provider(), command.providerUserId());
    if (existingIdentity.isPresent()) {
      final PlatformSocialIdentity identity = existingIdentity.get();
      LOG.info(
          "event=platform_login_success platformAccountId={} provider={} outcome=returning",
          identity.platformAccountId(),
          command.provider());
      recordOutcome(command.provider().name(), "returning");
      return new AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn(
          identity.platformAccountId());
    }

    final Optional<PlatformAccount> existingAccount = accounts.findByEmail(command.email());
    if (existingAccount.isEmpty()) {
      return linkBrandNewAccount(command);
    }
    return raisePendingLinkForExistingAccount(command, existingAccount.get());
  }

  private AuthenticatePlatformAccountWithSocialProviderResult linkBrandNewAccount(
      final AuthenticatePlatformAccountWithSocialProviderCommand command) {
    return transactionTemplate.execute(
        status -> {
          final PlatformAccount account = PlatformAccount.register(command.email());
          // The provider already proved control of this email (guarded above) — no reason to
          // make a brand-new social signup go through email verification a second time.
          account.verifyEmail();
          accounts.save(account);

          final PlatformSocialIdentity identity =
              PlatformSocialIdentity.link(
                  account.id(), command.provider(), command.providerUserId());
          socialIdentities.save(identity);

          LOG.info(
              "event=platform_login_success platformAccountId={} provider={} outcome=new_signup",
              account.id(),
              command.provider());
          recordOutcome(command.provider().name(), "new_signup");

          return new AuthenticatePlatformAccountWithSocialProviderResult.LoggedIn(account.id());
        });
  }

  private AuthenticatePlatformAccountWithSocialProviderResult raisePendingLinkForExistingAccount(
      final AuthenticatePlatformAccountWithSocialProviderCommand command,
      final PlatformAccount account) {
    final String rawToken = RefreshTokenSecret.generateRawValue();
    final PendingPlatformSocialLink pendingLink =
        PendingPlatformSocialLink.raise(
            account.id(),
            command.provider(),
            command.providerUserId(),
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(SocialLinkingPolicy.CONFIRMATION_TOKEN_TTL));
    pendingLinks.save(pendingLink);

    // Outside any transaction — same non-transactional-mail-send discipline as the tenant-tier
    // sibling, sent to the account's OWN email of record, never the provider's own claim.
    mailSender.sendPlatformSocialLinkConfirmation(
        account.email().value(), command.provider(), rawToken);

    LOG.info(
        "event=platform_login_confirmation_required platformAccountId={} provider={}",
        account.id(),
        command.provider());
    recordOutcome(command.provider().name(), "confirmation_required");

    return new AuthenticatePlatformAccountWithSocialProviderResult.ConfirmationRequired();
  }

  private void recordOutcome(final String providerName, final String outcome) {
    metrics.increment(
        LOGIN_METRIC, TIER_TAG, PLATFORM_TIER, "provider", providerName, OUTCOME_TAG, outcome);
  }

  private void recordFailure(final String reason) {
    metrics.increment(
        LOGIN_METRIC, TIER_TAG, PLATFORM_TIER, OUTCOME_TAG, "failure", "reason", reason);
  }
}
