package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0009 §2: admin-triggered, audited — never a background poller, same "manually-triggered,
 * audited operation" precedent CLAUDE.md §6 already establishes for signing-key rotation. Looks up
 * a TXT record at {@code _clavaris-challenge.<hostname>} and compares it against the challenge
 * token minted when the domain was (re-)requested; a mismatch or an empty result both collapse to
 * {@code FAILED} (see {@link DnsTxtRecordLookup}'s own Javadoc for why the two aren't distinguished
 * in v1).
 */
public class VerifyClientDomainOwnershipService implements VerifyClientDomainOwnershipUseCase {

  // BR-CLIENT-04's own ownership challenge — a stable, product-namespaced label so an operator
  // publishing the record can find guidance for it by name.
  private static final String CHALLENGE_PREFIX = "_clavaris-challenge.";

  private final OAuthClientRepository oauthClients;
  private final ClientDomainConfigRepository domainConfigs;
  private final DnsTxtRecordLookup dnsLookup;
  private final AuditEventRecorder auditEvents;

  public VerifyClientDomainOwnershipService(
      final OAuthClientRepository oauthClients,
      final ClientDomainConfigRepository domainConfigs,
      final DnsTxtRecordLookup dnsLookup,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.domainConfigs = domainConfigs;
    this.dnsLookup = dnsLookup;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public VerifyClientDomainOwnershipResult handle(
      final VerifyClientDomainOwnershipCommand command) {
    oauthClients
        .findById(command.oauthClientId())
        .filter(found -> found.organizationId().equals(command.organizationId()))
        .orElseThrow(() -> new OAuthClientNotFoundException(command.oauthClientId()));

    final ClientDomainConfig pending =
        domainConfigs
            .findByOAuthClientId(command.oauthClientId())
            .orElseThrow(() -> new ClientDomainConfigNotFoundException(command.oauthClientId()));

    final String hostname = pending.hostname().orElseThrow();
    final String expectedToken = pending.dnsTxtChallengeToken().orElseThrow();
    final boolean matched =
        dnsLookup.lookupTxtRecords(CHALLENGE_PREFIX + hostname).stream()
            .anyMatch(expectedToken::equals);

    final ClientDomainConfig result = matched ? pending.markVerified() : pending.markFailed();
    domainConfigs.save(result);

    auditEvents.write(
        command.actor(),
        matched ? "client_domain_config.verified" : "client_domain_config.verification_failed",
        "OAuthClient",
        command.oauthClientId().toString(),
        "organizationId=" + command.organizationId() + ", hostname=" + hostname);

    return new VerifyClientDomainOwnershipResult(result);
  }
}
