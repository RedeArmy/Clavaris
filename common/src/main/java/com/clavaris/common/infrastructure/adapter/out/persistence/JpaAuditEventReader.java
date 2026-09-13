package com.clavaris.common.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.AuditEventReader;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Implements {@link AuditEventReader}. {@code audit_events} has no composite index (or column) a
 * single {@code WHERE (target_type, target_id) IN (...)} tuple query could use directly, and {@link
 * SpringDataAuditEventJpaRepository} carries no derived-query methods (append-only until now, same
 * rationale {@code JpaAuditEventRecorder}'s own Javadoc documents) — so this builds one dynamic
 * JPQL query via {@link EntityManager} directly (same style {@code JpaAuditEventRecorder} already
 * uses for its own write, rather than introducing Spring Data Specifications for a single caller),
 * grouping the target refs by {@code targetType} first so each distinct type contributes one {@code
 * targetType = :tN AND targetId IN :idsN} clause, OR'd together — far fewer parameters than one
 * clause per individual target ref, and Postgres has no portable JPQL syntax for a composite-tuple
 * {@code IN} list.
 */
@Repository
class JpaAuditEventReader implements AuditEventReader {

  private final EntityManager entityManager;

  /* package */ JpaAuditEventReader(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  // PMD.OnlyOneReturn: the empty-targets short circuit avoids ever building a WHERE-less query
  // (which would match the whole table) — same "an empty input list means an empty result, not
  // 'no filter'" contract AuditEventReader's own Javadoc documents, worth its own early exit
  // rather than folding into the main return via a ternary.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public List<AuditEvent> findRecentForTargets(
      final List<AuditEventTargetRef> targets, final int maxResults) {
    if (targets.isEmpty()) {
      return List.of();
    }

    final Map<String, List<String>> idsByType = groupIdsByType(targets);
    final StringBuilder jpql = new StringBuilder("SELECT e FROM AuditEventEntity e WHERE ");
    for (int clauseIndex = 0; clauseIndex < idsByType.size(); clauseIndex++) {
      if (clauseIndex > 0) {
        jpql.append(" OR ");
      }
      jpql.append("(e.targetType = :type")
          .append(clauseIndex)
          .append(" AND e.targetId IN :ids")
          .append(clauseIndex)
          .append(')');
    }
    jpql.append(" ORDER BY e.occurredAt DESC");

    final TypedQuery<AuditEventEntity> query =
        entityManager.createQuery(jpql.toString(), AuditEventEntity.class);
    int paramIndex = 0;
    for (final Map.Entry<String, List<String>> entry : idsByType.entrySet()) {
      query.setParameter("type" + paramIndex, entry.getKey());
      query.setParameter("ids" + paramIndex, entry.getValue());
      paramIndex++;
    }
    query.setMaxResults(maxResults);

    return query.getResultList().stream().map(JpaAuditEventReader::toDomain).toList();
  }

  private static Map<String, List<String>> groupIdsByType(final List<AuditEventTargetRef> targets) {
    // LinkedHashMap: deterministic clause ordering — cosmetic (query correctness doesn't depend on
    // it), but keeps the generated JPQL stable/greppable across identical calls.
    final Map<String, List<String>> idsByType = new LinkedHashMap<>();
    for (final AuditEventTargetRef target : targets) {
      idsByType
          .computeIfAbsent(target.targetType(), key -> new java.util.ArrayList<>())
          .add(target.targetId());
    }
    return idsByType;
  }

  private static AuditEvent toDomain(final AuditEventEntity entity) {
    return AuditEvent.reconstitute(
        entity.getId(),
        new AuditActor(
            AuditActor.AuditActorType.valueOf(entity.getActorType()), entity.getActorId()),
        entity.getAction(),
        entity.getTargetType(),
        entity.getTargetId(),
        entity.getDetail(),
        entity.getOccurredAt());
  }
}
