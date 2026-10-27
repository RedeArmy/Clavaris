package com.clavaris.identity.application.usecase.connectsocialidentity;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.SocialIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-FUT-043: a signed-in End-User links a Google/GitHub identity to their own Account.
 *
 * <p>Unlike the social sign-in's automatic linking, no email match is involved: the person is
 * already authenticated and chose the link, so the provider's subject is simply attached. What is
 * refused is a provider identity that already belongs to another Account of this Organization
 * (CLAUDE.md §5: linking never crosses accounts) and a provider the Organization has not enabled
 * (BR-ID-12, re-checked here as the social sign-in re-checks it).
 */
public class ConnectSocialIdentityService implements ConnectSocialIdentityUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectSocialIdentityService.class);

  private final AccountRepository accounts;
  private final SocialIdentityRepository identities;
  private final OrganizationSocialLoginPolicyProvider policy;
  private final AuditEventRecorder auditEvents;

  public ConnectSocialIdentityService(
      final AccountRepository accounts,
      final SocialIdentityRepository identities,
      final OrganizationSocialLoginPolicyProvider policy,
      final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.identities = identities;
    this.policy = policy;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final ConnectSocialIdentityCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new IllegalStateException("Signed-in account no longer exists"));
    if (!policy.isProviderAllowed(account.organizationId(), command.provider())) {
      throw new SocialConnectionNotAllowedException();
    }
    final boolean providerAlreadyLinkedHere =
        identities.findAllByAccountId(account.id()).stream()
            .anyMatch(link -> link.provider() == command.provider());
    final boolean identityTakenInOrganization =
        identities
            .findByOrganizationIdAndProviderAndProviderUserId(
                account.organizationId(), command.provider(), command.providerUserId())
            .isPresent();
    if (providerAlreadyLinkedHere || identityTakenInOrganization) {
      throw new SocialIdentityAlreadyLinkedException();
    }
    try {
      identities.save(
          SocialIdentity.link(
              account.id(),
              account.organizationId(),
              command.provider(),
              command.providerUserId()));
    } catch (final DataIntegrityViolationException raced) {
      throw new SocialIdentityAlreadyLinkedException(raced);
    }
    auditEvents.write(
        AuditActor.account(account.id().value()),
        "account.social_identity_connected",
        "Account",
        account.id().value().toString(),
        command.provider().name(),
        account.organizationId().value());
    LOG.info(
        "event=social_identity_connected accountId={} provider={}",
        account.id(),
        command.provider());
  }
}
