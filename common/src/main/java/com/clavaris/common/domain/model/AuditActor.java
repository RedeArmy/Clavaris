package com.clavaris.common.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * TD-SEC-007: identifies who performed an audited admin/operator action — a {@code PlatformAccount}
 * acting on its own behalf through the self-service dashboard (ADR-0012), or a {@code
 * PlatformClient} calling the {@code /api/v1/admin/**} REST surface via {@code client_credentials}
 * (BR-PLATFORM-02). Deliberately a plain type, not a reference to either module's own domain entity
 * — this class lives in the shared kernel precisely so it never depends on identity-module,
 * organization-module, or client-registry-module, and {@code id} is a string, not a typed {@code
 * UUID}, because a {@code PlatformClient}'s own {@code client_id} isn't always one (see {@link
 * #platformClient}).
 *
 * <p>Resolved once, at the web/infrastructure layer where an {@code Authentication} is actually
 * available ({@code SecurityContextHolder}), and passed down as a plain value from there — the same
 * "read the framework type at the edge, hand the application layer an opaque string" pattern {@code
 * RateLimitIdentifiers} already established for rate-limit keys.
 */
@SuppressWarnings("PMD.ShortVariable")
public record AuditActor(AuditActorType type, String id) {

  public AuditActor {
    Objects.requireNonNull(type, "type must not be null");
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
  }

  public enum AuditActorType {
    PLATFORM_ACCOUNT,
    PLATFORM_CLIENT
  }

  public static AuditActor platformAccount(final UUID platformAccountId) {
    return new AuditActor(
        AuditActorType.PLATFORM_ACCOUNT,
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null").toString());
  }

  // A PlatformClient's client_id is an operator-chosen string (BootstrapPlatformClientService),
  // never a UUID by contract — kept as the raw String SAS's own Authentication#getName() already
  // resolves it to, not parsed into anything narrower.
  public static AuditActor platformClient(final String clientId) {
    return new AuditActor(AuditActorType.PLATFORM_CLIENT, clientId);
  }
}
