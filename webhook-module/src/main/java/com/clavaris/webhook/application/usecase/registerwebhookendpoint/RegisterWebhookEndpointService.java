package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Orchestration for {@link RegisterWebhookEndpointUseCase}. Generates the raw signing secret
 * server-side, never accepts one from the caller — same "a machine credential is stronger generated
 * here than accepted from an operator's own choice" reasoning client-registry-module's own {@code
 * RegisterOAuthClientService} already establishes for {@code client_secret}.
 *
 * <p>BR-WEBHOOK-08 (SDE-III review, 2026-09-15): enforces {@link #MAX_ENDPOINTS_PER_ORGANIZATION},
 * a per-Organization cap this use case previously had no upper bound on at all. {@code
 * com.clavaris.webhook.infrastructure.adapter.out.persistence.JpaWebhookEndpointRepository}'s own
 * {@code findActiveByOrganizationIdAndEventType} Javadoc already assumed "every Organization's own
 * endpoint count is small (a handful, not thousands)" — that assumption was never actually true by
 * construction. {@code DispatchOutboxEventsService} fans out one {@code WebhookDelivery} row per
 * active, subscribed endpoint for every event an Organization produces, inside one shared
 * dispatcher every tenant's delivery latency depends on — an Organization with no registration
 * limit could multiply its own event volume by an arbitrary factor, degrading the dispatcher for
 * every other tenant, not just itself. A fixed system-wide constant, not yet a
 * per-Organization-tunable ceiling (v1 scope, same "operator-managed only in v1" posture the
 * rate-limit capacity ceiling already documents) — there is no self-service path to raise it.
 */
public class RegisterWebhookEndpointService implements RegisterWebhookEndpointUseCase {

  // 256 bits — same order of magnitude/encoding choice as RegisterOAuthClientService's own
  // identical secret generation.
  private static final int SECRET_LENGTH = 32;

  // BR-WEBHOOK-08: see this class's own Javadoc for the dispatcher cost-multiplier this bounds.
  // Generous for any real consumer (a handful of environments/event-type-specific handlers), tight
  // enough to keep one Organization's worst-case per-event fan-out cost bounded and predictable.
  // Package-private (not private): RegisterWebhookEndpointServiceTest asserts against it directly
  // rather than duplicating the literal 25 as a magic number of its own.
  @SuppressWarnings("PMD.LongVariable") // names exactly what it holds — same precedent
  // MAX_DISPLAY_NAME_LENGTH's own class-level suppression documents for an identically-shaped name.
  /* package */ static final int MAX_ENDPOINTS_PER_ORGANIZATION = 25;

  private final WebhookEndpointRepository endpoints;
  private final OrganizationExistsChecker orgExistsChecker;
  private final WebhookUrlSsrfGuard ssrfGuard;
  private final WebhookSigningSecretCipher cipher;
  private final AuditEventRecorder auditEvents;
  private final SecureRandom secureRandom = new SecureRandom();

  public RegisterWebhookEndpointService(
      final WebhookEndpointRepository endpoints,
      final OrganizationExistsChecker orgExistsChecker,
      final WebhookUrlSsrfGuard ssrfGuard,
      final WebhookSigningSecretCipher cipher,
      final AuditEventRecorder auditEvents) {
    this.endpoints = endpoints;
    this.orgExistsChecker = orgExistsChecker;
    this.ssrfGuard = ssrfGuard;
    this.cipher = cipher;
    this.auditEvents = auditEvents;
  }

  @Override
  public RegisterWebhookEndpointResult handle(final RegisterWebhookEndpointCommand command) {
    // BR-ORG-02 (this module's own equivalent): never let an endpoint be registered under a
    // non-existent Organization — same reasoning RegisterOAuthClientService's own identical check
    // already establishes, including why it must be an application-layer check (no FK enforces
    // this across modules; cross-module migration ordering isn't guaranteed).
    if (!orgExistsChecker.exists(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }
    // BR-WEBHOOK-08: cheapest remaining check first, same "reject before doing real work" ordering
    // as the Organization check above — no point consulting the SSRF guard (a DNS resolution) for a
    // registration that's going to be rejected on count alone.
    if (endpoints.countByOrganizationId(command.organizationId())
        >= MAX_ENDPOINTS_PER_ORGANIZATION) {
      throw new WebhookEndpointLimitExceededException(
          command.organizationId(), MAX_ENDPOINTS_PER_ORGANIZATION);
    }
    // TD-SEC-053: before anything is persisted — WebhookEndpoint.requireValidUrl only ever checks
    // the scheme (BR-WEBHOOK-07), never where the host actually resolves to.
    ssrfGuard.requireSafeToRegister(command.url());

    final String rawSecret = generateRawSecret();
    final WebhookEndpoint endpoint =
        WebhookEndpoint.register(
            command.organizationId(),
            command.url(),
            command.description(),
            command.subscribedEventTypes(),
            cipher.encrypt(rawSecret));

    // TD-PERF-019: insert, not save — WebhookEndpoint.register always mints a brand-new
    // aggregate. See WebhookEndpointRepository#insert's own Javadoc.
    endpoints.insert(endpoint);
    auditEvents.write(
        command.actor(),
        "webhook_endpoint.registered",
        "WebhookEndpoint",
        endpoint.id().toString(),
        "organizationId=" + command.organizationId());
    return new RegisterWebhookEndpointResult(endpoint, rawSecret);
  }

  private String generateRawSecret() {
    final byte[] bytes = new byte[SECRET_LENGTH];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
