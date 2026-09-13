package com.clavaris.common.application.port;

import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import java.util.List;

/**
 * Read side of {@link AuditEventRecorder} (TD-SEC-007) — genuinely new, no query capability existed
 * for {@code audit_events} before this. Deliberately NOT a per-caller filter interface (no
 * organizationId, no actor, no date range parameters here): {@code audit_events} carries no {@code
 * organization_id} column, so any Organization-scoped (or otherwise entity-scoped) query is the
 * caller's own responsibility to build as a {@link AuditEventTargetRef} list first — this port only
 * ever answers "give me the most recent rows matching any of these exact target references," the
 * one primitive every such caller actually needs.
 *
 * @param targets the {@code (targetType, targetId)} pairs to match, typically one entry per
 *     resource a caller has already confirmed it owns — an empty list matches nothing (never "no
 *     filter, return everything"), so a caller that resolved zero owned resources gets back an
 *     empty result, not an unbounded scan of every Organization's own audit trail.
 * @param maxResults a hard cap, same "cheap to hold in memory, no real pagination yet" posture
 *     {@code ListWebhookDeliveriesForEndpointService}'s own {@code MAX_RESULTS} already establishes
 *     — newest first.
 */
@FunctionalInterface
public interface AuditEventReader {

  List<AuditEvent> findRecentForTargets(List<AuditEventTargetRef> targets, int maxResults);
}
