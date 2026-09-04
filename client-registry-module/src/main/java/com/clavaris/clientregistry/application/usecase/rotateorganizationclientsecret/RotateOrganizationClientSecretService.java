package com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientSecretGenerator;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Same rationale as {@code rotateplatformclientsecret.RotatePlatformClientSecretService} — the
 * real, code-driven way to rotate this credential, never bundled with revocation.
 */
@SuppressWarnings("PMD.LongVariable")
public class RotateOrganizationClientSecretService
    implements RotateOrganizationClientSecretUseCase {

  private final OrganizationClientRepository organizationClients;
  private final ClientSecretHasher hasher;
  private final OrganizationClientSecretGenerator secretGenerator;
  private final AuditEventRecorder auditEvents;

  public RotateOrganizationClientSecretService(
      final OrganizationClientRepository organizationClients,
      final ClientSecretHasher hasher,
      final OrganizationClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    this.organizationClients = organizationClients;
    this.hasher = hasher;
    this.secretGenerator = secretGenerator;
    this.auditEvents = auditEvents;
  }

  @Override
  public RotateOrganizationClientSecretResult handle(
      final RotateOrganizationClientSecretCommand command) {
    final OrganizationClient existing =
        organizationClients
            .findByClientId(command.clientId())
            .orElseThrow(() -> new OrganizationClientNotFoundException(command.clientId()));

    final String rawSecret = secretGenerator.generate();
    final OrganizationClient rotated = existing.rotateSecret(hasher.hash(rawSecret));
    organizationClients.save(rotated);

    auditEvents.write(
        command.actor(),
        "organization_client.secret_rotated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());

    return new RotateOrganizationClientSecretResult(command.clientId(), rawSecret);
  }
}
