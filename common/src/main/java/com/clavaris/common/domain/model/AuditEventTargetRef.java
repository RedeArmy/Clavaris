package com.clavaris.common.domain.model;

/**
 * One {@code (targetType, targetId)} pair to match against {@code audit_events} (TD-SEC-007) —
 * exactly the shape every {@link AuditEventRecorder#write} call already keys its own row by. A
 * caller resolving "every audit event touching Organization X" builds a {@code List} of these (the
 * Organization's own id, plus every resource id it owns across whichever modules are in scope) and
 * hands it to {@code AuditEventReader.findRecentForTargets} — {@code audit_events} itself carries
 * no {@code organization_id} column by design (see that table's own migration comment: {@code
 * common} must never depend on any single business module's schema), so this fan-out is how an
 * Organization-scoped query gets built without one.
 */
public record AuditEventTargetRef(String targetType, String targetId) {}
