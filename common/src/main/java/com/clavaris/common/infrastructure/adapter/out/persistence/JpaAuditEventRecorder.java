package com.clavaris.common.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.application.port.AuditTenantResolver;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the outbound port (TD-SEC-007). Persists to {@code audit_events} AND emits a
 * structured {@code event=audit_recorded} log line, the same dual convention TD-SEC-014/016/017
 * already established elsewhere — the durable row is the record of truth an investigation queries,
 * the log line is what an operator watching a live tail sees in the moment.
 *
 * <p>TD-PERF-019: calls {@link EntityManager#persist} directly, not {@code
 * SpringDataAuditEventJpaRepository#save} — an audit trail is genuinely append-only ({@code
 * AuditEvent.of} always mints a brand-new id, {@link #write} is this class's only write method, and
 * nothing anywhere ever re-saves an already-persisted row), so unlike the update-capable
 * repositories this row's own register entry named, there is no ambiguity here for {@code
 * persist()} to get wrong — {@code merge()}'s mandatory pre-existence {@code SELECT} was pure waste
 * on this table specifically, confirmed insert-only by reading every call site.
 * {@code @Transactional} on {@code write} itself — a raw {@code persist} call needs one already
 * open on the current thread, unlike {@code SimpleJpaRepository#save}'s own built-in one; every
 * real caller already has one, this guards a bare test-fixture caller too.
 */
@Repository
class JpaAuditEventRecorder implements AuditEventRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(JpaAuditEventRecorder.class);

  private final EntityManager entityManager;
  private final ObjectProvider<AuditTenantResolver> tenantResolver;

  /* package */ JpaAuditEventRecorder(
      final EntityManager entityManager, final ObjectProvider<AuditTenantResolver> tenantResolver) {
    this.entityManager = entityManager;
    this.tenantResolver = tenantResolver;
  }

  @Override
  @Transactional
  public void write(
      final AuditActor actor,
      final String action,
      final String targetType,
      final String targetId,
      final String detail) {
    final AuditEvent event = AuditEvent.of(actor, action, targetType, targetId, detail);
    persist(event, organizationOf(event));
  }

  @Override
  @Transactional
  public void write(
      final AuditActor actor,
      final String action,
      final String targetType,
      final String targetId,
      final String detail,
      final UUID organizationId) {
    persist(AuditEvent.of(actor, action, targetType, targetId, detail), organizationId);
  }

  private void persist(final AuditEvent event, final UUID organizationId) {
    entityManager.persist(
        new AuditEventEntity(
            event.id(),
            event.actor().type().name(),
            event.actor().id(),
            event.action(),
            event.targetType(),
            event.targetId().orElse(null),
            event.detail().orElse(null),
            event.occurredAt(),
            organizationId));
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

  // TD-ARCH-038: the tenant key is a convenience for reading the log, never a reason to lose the
  // event. A resolver that is absent (this module's own tests) or cannot answer leaves it null.
  private UUID organizationOf(final AuditEvent event) {
    final AuditTenantResolver resolver = tenantResolver.getIfAvailable();
    if (resolver == null || event.targetId().isEmpty()) {
      return null;
    }
    try {
      return resolver.organizationOf(event.targetType(), event.targetId().get()).orElse(null);
    } catch (final DataAccessException e) {
      LOG.warn(
          "event=audit_tenant_unresolved action={} exceptionType={}",
          event.action(),
          e.getClass().getName());
      return null;
    }
  }
}
