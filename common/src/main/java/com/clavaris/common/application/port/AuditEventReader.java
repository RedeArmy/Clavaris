package com.clavaris.common.application.port;

import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.AuditEventTargetRef;
import com.clavaris.common.domain.model.KeysetCursor;
import java.util.List;
import java.util.UUID;

/**
 * Read side of {@link AuditEventRecorder} (TD-SEC-007).
 *
 * <p>Two questions, answered two ways. "What happened to these specific things" ({@link
 * #findRecentForTargets}) takes exact {@code (targetType, targetId)} pairs the caller has already
 * confirmed it owns; an empty list matches nothing, never "no filter". "What happened in this
 * Organization" ({@link #findPageForOrganization}) uses the tenant key {@code audit_events} carries
 * since TD-ARCH-038, so it needs no list of ids and covers every kind of event the Organization
 * produced, accounts included.
 */
public interface AuditEventReader {

  /**
   * The most recent rows matching any of {@code targets}, newest first, at most {@code maxResults}.
   * A hard cap, not pagination: callers use it for small, per-resource histories.
   */
  List<AuditEvent> findRecentForTargets(List<AuditEventTargetRef> targets, int maxResults);

  /**
   * One page of an Organization's audit log, newest first. {@code olderThan} is the cursor of the
   * last row of the previous page ({@code null} for the first page); {@code limit} rows are
   * returned at most, so a caller wanting to know whether another page exists asks for one more
   * than it shows.
   */
  List<AuditEvent> findPageForOrganization(UUID organizationId, KeysetCursor olderThan, int limit);
}
