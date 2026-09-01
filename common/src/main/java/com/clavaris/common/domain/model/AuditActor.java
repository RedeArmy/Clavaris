package com.clavaris.common.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * TD-SEC-007: identifies who performed an audited action — a {@code PlatformAccount} acting on its
 * own behalf through the self-service dashboard (ADR-0012), a {@code PlatformClient} calling the
 * {@code /api/v1/admin/**} REST surface via {@code client_credentials} (BR-PLATFORM-02), or (TD-
 * SEC-034, BR-ID-13/14) a tenant {@code Account} acting on its own behalf through a self-service
 * page (e.g. revoking its own live session). Deliberately a plain type, not a reference to either
 * module's own domain entity — this class lives in the shared kernel precisely so it never depends
 * on identity-module, organization-module, or client-registry-module, and {@code id} is a string,
 * not a typed {@code UUID}, because a {@code PlatformClient}'s own {@code client_id} isn't always
 * one (see {@link #platformClient}).
 *
 * <p>No schema change was needed to add the {@link AuditActorType#ACCOUNT} variant — {@code
 * audit_events.actor_type} is a plain {@code varchar(32)}, not a native Postgres enum with a CHECK
 * constraint (its own migration's Javadoc explains why: this table intentionally carries no
 * business-module-specific typing at all).
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
    PLATFORM_CLIENT,
    ACCOUNT
  }

  public static AuditActor platformAccount(final UUID platformAccountId) {
    return new AuditActor(
        AuditActorType.PLATFORM_ACCOUNT,
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null").toString());
  }

  // TD-SEC-034: a tenant Account acting on its own behalf (self-service session revoke, BR-ID-13;
  // the passive "new device" detection its own login triggers, BR-ID-14) — accountId, never an
  // email/PII, same "opaque internal identifier, not a person's real-world identity" bar every
  // other actor id on this type already meets.
  public static AuditActor account(final UUID accountId) {
    return new AuditActor(
        AuditActorType.ACCOUNT,
        Objects.requireNonNull(accountId, "accountId must not be null").toString());
  }

  // A PlatformClient's client_id is an operator-chosen string (BootstrapPlatformClientService),
  // never a UUID by contract — kept as the raw String SAS's own Authentication#getName() already
  // resolves it to, not parsed into anything narrower.
  public static AuditActor platformClient(final String clientId) {
    return new AuditActor(AuditActorType.PLATFORM_CLIENT, clientId);
  }
}
