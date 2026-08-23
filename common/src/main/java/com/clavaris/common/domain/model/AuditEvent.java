package com.clavaris.common.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * TD-SEC-007: an immutable, append-only record of one admin/operator action — Organization
 * creation, a rate-limit-policy change, a signing-key rotation, a {@code PlatformClient} rotation,
 * and every future action this register or a business rule names as requiring one. Distinct from
 * the {@code event=} structured log lines TD-SEC-014/016/017 already established for the
 * login/token surface: those are grep-able signal in a log stream that can rotate away, this is a
 * durable, queryable Postgres row — BR-ADMIN-01 (impersonation, not yet built) and any future "who
 * did this and when" investigation both need the latter, not the former.
 *
 * <p>Never carries PII or a secret in {@code detail} — same BR-DATA-01 discipline as every other
 * logged/persisted event in this codebase (TD-SEC-014's own login-event logging, TD-SEC-016/017's
 * token event logging). {@code detail} is a short, human-readable, non-secret summary (e.g. {@code
 * "requestsPerMinute=800"}, {@code "kid=a1b2c3"}) — never an email, a token, a password, or a
 * client secret.
 */
// Same record-style-accessor PMD suppressions as RateLimitPolicy/Organization, same rationale:
// plain accessors matching the field name are the deliberate convention this codebase's domain
// models use throughout, not an organically grown JavaBean that should be renamed.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.DataClass"
})
public final class AuditEvent {

  private final UUID id;
  private final AuditActor actor;
  private final String action;
  private final String targetType;
  private final String targetId;
  private final String detail;
  private final Instant occurredAt;

  private AuditEvent(
      final UUID id,
      final AuditActor actor,
      final String action,
      final String targetType,
      final String targetId,
      final String detail,
      final Instant occurredAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.actor = Objects.requireNonNull(actor, "actor must not be null");
    this.action = requireNonBlank(action, "action");
    this.targetType = requireNonBlank(targetType, "targetType");
    this.targetId = targetId;
    this.detail = detail;
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
  }

  /**
   * A brand-new event, timestamped now — the only way application code ever creates one; there is
   * deliberately no setter/mutator anywhere on this class, an audit trail that could be edited
   * after the fact would defeat its own purpose. Named {@code of}, not {@code record}: SonarCloud
   * flags a bare {@code record} identifier as a restricted one (Java's own record-type keyword,
   * contextual since Java 16), even though a method legitimately named that still compiles.
   */
  public static AuditEvent of(
      final AuditActor actor,
      final String action,
      final String targetType,
      final String targetId,
      final String detail) {
    return new AuditEvent(
        UUID.randomUUID(), actor, action, targetType, targetId, detail, Instant.now());
  }

  public static AuditEvent reconstitute(
      final UUID id,
      final AuditActor actor,
      final String action,
      final String targetType,
      final String targetId,
      final String detail,
      final Instant occurredAt) {
    return new AuditEvent(id, actor, action, targetType, targetId, detail, occurredAt);
  }

  private static String requireNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  public UUID id() {
    return id;
  }

  public AuditActor actor() {
    return actor;
  }

  public String action() {
    return action;
  }

  public String targetType() {
    return targetType;
  }

  public Optional<String> targetId() {
    return Optional.ofNullable(targetId);
  }

  public Optional<String> detail() {
    return Optional.ofNullable(detail);
  }

  public Instant occurredAt() {
    return occurredAt;
  }
}
