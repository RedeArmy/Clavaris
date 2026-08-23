package com.clavaris.common.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port (TD-SEC-007). Persists to {@code audit_events} AND emits a
 * structured {@code event=audit_recorded} log line, the same dual convention TD-SEC-014/016/017
 * already established elsewhere — the durable row is the record of truth an investigation queries,
 * the log line is what an operator watching a live tail sees in the moment.
 */
@Repository
class JpaAuditEventRecorder implements AuditEventRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(JpaAuditEventRecorder.class);

  private final SpringDataAuditEventJpaRepository events;

  /* package */ JpaAuditEventRecorder(final SpringDataAuditEventJpaRepository events) {
    this.events = events;
  }

  @Override
  public void write(
      final AuditActor actor,
      final String action,
      final String targetType,
      final String targetId,
      final String detail) {
    final AuditEvent event = AuditEvent.of(actor, action, targetType, targetId, detail);
    events.save(
        new AuditEventEntity(
            event.id(),
            event.actor().type().name(),
            event.actor().id(),
            event.action(),
            event.targetType(),
            event.targetId().orElse(null),
            event.detail().orElse(null),
            event.occurredAt()));
    // Guarded, not unconditional: same rationale as TokenRevocationEventLogger's own identical
    // guard — SonarCloud (rule S2629, unlike PMD's GuardLogStatement elsewhere in this codebase,
    // which treats plain accessor arguments as a false positive) won't assume the accessor chain
    // below is free. isInfoEnabled() skips it entirely when INFO is disabled.
    // BR-DATA-01: actor_id/target_id are never raw PII by this port's own contract (a
    // PlatformAccountId/organizationId/client_id/kid, never an email) — safe to log directly, same
    // bar TD-SEC-014 already applies to accountId/organizationId elsewhere.
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "event=audit_recorded actor_type={} actor_id={} action={} target_type={} target_id={}",
          event.actor().type(),
          event.actor().id(),
          event.action(),
          event.targetType(),
          event.targetId().orElse("-"));
    }
  }
}
