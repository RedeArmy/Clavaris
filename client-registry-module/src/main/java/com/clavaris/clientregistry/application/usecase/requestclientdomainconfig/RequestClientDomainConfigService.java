package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0009 §2. Operator-managed only in v1, same posture as every other per-client policy surface
 * (see {@code SetClientBrandingService}'s own identical rationale). A changed {@code hostname}/
 * {@code mode} always mints a fresh challenge token and resets to {@code PENDING} (see {@link
 * ClientDomainConfig#request}/{@link ClientDomainConfig#reRequest}'s own Javadoc for why); an
 * unchanged {@code hostname}/{@code mode} with only {@code embeddingOrigin} different updates that
 * one field in place via {@link ClientDomainConfig#withEmbeddingOrigin}, preserving an existing
 * {@code VERIFIED} status rather than forcing a pointless re-verification.
 */
public class RequestClientDomainConfigService implements RequestClientDomainConfigUseCase {

  private final OAuthClientRepository oauthClients;
  private final ClientDomainConfigRepository domainConfigs;
  private final AuditEventRecorder auditEvents;

  public RequestClientDomainConfigService(
      final OAuthClientRepository oauthClients,
      final ClientDomainConfigRepository domainConfigs,
      final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.domainConfigs = domainConfigs;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public RequestClientDomainConfigResult handle(final RequestClientDomainConfigCommand command) {
    oauthClients
        .findById(command.oauthClientId())
        .filter(found -> found.organizationId().equals(command.organizationId()))
        .orElseThrow(() -> new OAuthClientNotFoundException(command.oauthClientId()));

    domainConfigs
        .findByHostname(command.hostname())
        .filter(claimedBy -> !claimedBy.oauthClientId().equals(command.oauthClientId()))
        .ifPresent(
            claimedBy -> {
              throw new HostnameAlreadyClaimedException(command.hostname());
            });

    final ClientDomainConfig config =
        domainConfigs
            .findByOAuthClientId(command.oauthClientId())
            .map(existing -> applyTo(existing, command))
            .orElseGet(
                () ->
                    ClientDomainConfig.request(
                        command.oauthClientId(),
                        command.mode(),
                        command.hostname(),
                        command.embeddingOrigin()));

    domainConfigs.save(config);

    auditEvents.write(
        command.actor(),
        "client_domain_config.requested",
        "OAuthClient",
        command.oauthClientId().toString(),
        "organizationId=" + command.organizationId() + ", hostname=" + command.hostname());

    return new RequestClientDomainConfigResult(config);
  }

  // The hostname/mode pair is what the DNS challenge actually proves ownership of — unchanged,
  // this is only an embeddingOrigin update (see this class's own Javadoc); changed, it's a real
  // re-request that must re-prove ownership.
  private static ClientDomainConfig applyTo(
      final ClientDomainConfig existing, final RequestClientDomainConfigCommand command) {
    final boolean unchanged =
        existing.hostname().filter(command.hostname()::equals).isPresent()
            && existing.mode().filter(command.mode()::equals).isPresent();
    return unchanged
        ? existing.withEmbeddingOrigin(command.embeddingOrigin())
        : existing.reRequest(command.mode(), command.hostname(), command.embeddingOrigin());
  }
}
