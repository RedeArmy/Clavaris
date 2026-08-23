package com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * TD-SEC-018: the first real, code-driven way to rotate the single highest-value credential in the
 * whole system — before this, {@code incident-response-platform-client-compromise.md} §3a
 * documented that only raw SQL against production could do it. Deliberately does NOT touch whether
 * the client is {@code active} — rotation and revocation are two independent, separately auditable
 * actions (same "never bundle two actions" convention {@code RegisterOAuthClient}'s own Javadoc
 * already applies to Organization creation vs. client registration).
 */
public class RotatePlatformClientSecretService implements RotatePlatformClientSecretUseCase {

  private final PlatformClientRepository platformClients;
  private final ClientSecretHasher hasher;
  private final PlatformClientSecretGenerator secretGenerator;
  private final AuditEventRecorder auditEvents;

  public RotatePlatformClientSecretService(
      final PlatformClientRepository platformClients,
      final ClientSecretHasher hasher,
      final PlatformClientSecretGenerator secretGenerator,
      final AuditEventRecorder auditEvents) {
    this.platformClients = platformClients;
    this.hasher = hasher;
    this.secretGenerator = secretGenerator;
    this.auditEvents = auditEvents;
  }

  @Override
  public RotatePlatformClientSecretResult handle(final RotatePlatformClientSecretCommand command) {
    final PlatformClient existing =
        platformClients
            .findByClientId(command.clientId())
            .orElseThrow(() -> new PlatformClientNotFoundException(command.clientId()));

    final String rawSecret = secretGenerator.generate();
    final PlatformClient rotated = existing.rotateSecret(hasher.hash(rawSecret));
    platformClients.save(rotated);

    // Never the raw secret, never the hash — same BR-DATA-01 discipline as every other audited
    // action in this codebase.
    auditEvents.record(
        command.actor(),
        "platform_client.secret_rotated",
        "PlatformClient",
        command.clientId(),
        null);

    return new RotatePlatformClientSecretResult(command.clientId(), rawSecret);
  }
}
