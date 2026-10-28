package com.clavaris.identity.application.usecase.disconnectsocialidentity;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.SocialIdentityRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.event.SocialIdentityUnlinkedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.SocialIdentity;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-FUT-043: an End-User unlinks a Google/GitHub identity from their own Account.
 *
 * <p>BR-ID-02's "never zero sign-in methods" holds at registration through a deferred database
 * trigger, which cannot see a later delete, so this use case enforces it: the link may go only if a
 * password, another linked provider or a passkey remains. Otherwise the person would lock
 * themselves out of the only Account they have in this Organization.
 */
public class DisconnectSocialIdentityService implements DisconnectSocialIdentityUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DisconnectSocialIdentityService.class);

  private final AccountRepository accounts;
  private final SocialIdentityRepository identities;
  private final WebAuthnCredentialRepository passkeys;
  private final EventOutboxWriter outbox;
  private final AuditEventRecorder auditEvents;

  public DisconnectSocialIdentityService(
      final AccountRepository accounts,
      final SocialIdentityRepository identities,
      final WebAuthnCredentialRepository passkeys,
      final EventOutboxWriter outbox,
      final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.identities = identities;
    this.passkeys = passkeys;
    this.outbox = outbox;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final DisconnectSocialIdentityCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new IllegalStateException("Signed-in account no longer exists"));
    final List<SocialIdentity> linked = identities.findAllByAccountId(account.id());
    if (linked.stream().noneMatch(link -> link.provider() == command.provider())) {
      throw new SocialIdentityNotLinkedException();
    }
    final boolean anotherWayIn =
        account.passwordCredential().isPresent()
            || linked.stream().anyMatch(link -> link.provider() != command.provider())
            || !passkeys.findAllByAccountId(account.id()).isEmpty();
    if (!anotherWayIn) {
      throw new LastSignInMethodException();
    }
    identities.deleteByAccountIdAndProvider(account.id(), command.provider());
    outbox.write(
        "social_identity.unlinked",
        account.id(),
        account.organizationId(),
        SocialIdentityUnlinkedEvent.of(account.id(), account.organizationId(), command.provider()));
    auditEvents.write(
        command.actor(),
        "account.social_identity_disconnected",
        "Account",
        account.id().value().toString(),
        command.provider().name(),
        account.organizationId().value());
    LOG.info(
        "event=social_identity_disconnected accountId={} provider={}",
        account.id(),
        command.provider());
  }
}
