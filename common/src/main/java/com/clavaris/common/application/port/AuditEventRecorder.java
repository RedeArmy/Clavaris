package com.clavaris.common.application.port;

import com.clavaris.common.domain.model.AuditActor;

/**
 * TD-SEC-007: the outbound port every business module's own use-case services call, directly, from
 * inside their existing {@code @Transactional} method — same rationale as identity-module's own
 * {@code EventOutboxWriter}: the audit row must land in the SAME database transaction as the domain
 * state change it records, or a crash between the two would let a real action go unaudited.
 * Business modules depend on {@code common} for exactly this one cross-cutting concern — the
 * threshold client-registry-module's own {@code pom.xml} already names ("refactor into a shared
 * utility only once a third module needs the same thing") is what justifies it existing here at
 * all, not in one module with the others duplicating it.
 *
 * @param action a short, stable, dotted verb-in-past-tense identifier, e.g. {@code
 *     "organization.created"}, {@code "rate_limit_policy.set"}, {@code "signing_key.rotated"},
 *     {@code "platform_client.rotated"} — never freeform prose, so a real audit query can filter by
 *     it reliably.
 * @param targetType the kind of thing acted on, e.g. {@code "Organization"}, {@code "SigningKey"},
 *     {@code "PlatformClient"}.
 * @param targetId the specific instance's id (its own natural string form — a UUID's {@code
 *     toString()}, a {@code kid}, a {@code client_id}), or {@code null} when the action has no
 *     single natural target id.
 * @param detail a short, non-PII, non-secret human-readable summary — see {@code AuditEvent}'s own
 *     Javadoc for what never belongs here.
 */
@FunctionalInterface
public interface AuditEventRecorder {

  // Named write(), not record() — same verb EventOutboxWriter already uses for an identically-
  // shaped outbound port, and SonarCloud flags a bare `record` identifier as a restricted one
  // (Java's own record-type keyword, contextual since Java 16) regardless of that this method
  // legitimately compiles fine either way.
  void write(AuditActor actor, String action, String targetType, String targetId, String detail);
}
