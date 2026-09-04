package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationEnvironmentChecker;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OrganizationExistsChecker;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import java.util.UUID;

/**
 * Orchestration for {@link CreateOrganizationClientUseCase} — ADR-0023's own core write path.
 * Unlike ADR-0022's social credentials, this is available in **both** environments, not
 * PRODUCTION-only: Clerk's own model gives every app (dev or prod) its own Secret Key,
 * differentiated by the {@code sk_test_}/{@code sk_live_} prefix — same mechanic {@code
 * RegisterOAuthClientService} already established for {@code test_}/{@code live_}, reused here via
 * the same {@link OrganizationEnvironmentChecker} port.
 */
@SuppressWarnings("PMD.LongVariable")
public class CreateOrganizationClientService implements CreateOrganizationClientUseCase {

  private static final String DEVELOPMENT_CLIENT_ID_PREFIX = "sk_test_";
  private static final String PRODUCTION_CLIENT_ID_PREFIX = "sk_live_";

  private final OrganizationClientRepository organizationClients;
  private final OrganizationExistsChecker orgExistsChecker;
  private final OrganizationEnvironmentChecker environmentChecker;
  private final ClientSecretHasher hasher;
  private final OrganizationClientSecretGenerator secretGenerator;
  private final AuditEventRecorder auditEvents;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as every
  // other multi-collaborator constructor in this codebase.
  public CreateOrganizationClientService(
      final OrganizationClientRepository organizationClients,
      final OrganizationExistsChecker orgExistsChecker,
      final OrganizationEnvironmentChecker environmentChecker,
      final ClientSecretHasher hasher,
      final OrganizationClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    this.organizationClients = organizationClients;
    this.orgExistsChecker = orgExistsChecker;
    this.environmentChecker = environmentChecker;
    this.hasher = hasher;
    this.secretGenerator = secretGenerator;
    this.auditEvents = auditEvents;
  }

  @Override
  public CreateOrganizationClientResult handle(final CreateOrganizationClientCommand command) {
    // BR-ORG-02's own precedent (RegisterOAuthClientService): never let a credential be created
    // under a non-existent Organization — the FK-less column (this table's own migration comment)
    // relies entirely on this application-layer check.
    if (!orgExistsChecker.exists(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    final String clientIdPrefix =
        environmentChecker.isDevelopment(command.organizationId())
            ? DEVELOPMENT_CLIENT_ID_PREFIX
            : PRODUCTION_CLIENT_ID_PREFIX;
    final String clientId = clientIdPrefix + UUID.randomUUID();
    final String rawSecret = secretGenerator.generate();
    final OrganizationClient organizationClient =
        OrganizationClient.register(
            command.organizationId(), clientId, hasher.hash(rawSecret), command.allowedScopes());

    organizationClients.save(organizationClient);

    // Never the raw secret, never the hash — same BR-DATA-01 discipline as every other audited
    // secret-bearing action in this codebase (e.g. RotatePlatformClientSecretService).
    auditEvents.write(
        command.actor(),
        "organization_client.created",
        "Organization",
        command.organizationId().toString(),
        "clientId=" + clientId);
    return new CreateOrganizationClientResult(organizationClient, rawSecret);
  }
}
