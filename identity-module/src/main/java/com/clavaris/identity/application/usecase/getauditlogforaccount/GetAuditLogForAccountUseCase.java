package com.clavaris.identity.application.usecase.getauditlogforaccount;

import com.clavaris.common.domain.model.AuditEvent;
import java.util.List;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "View log" menu item. Unlike {@code
 * organization-module}'s own {@code GetAuditLogForOrganizationUseCase} (which explicitly excludes
 * Account-level events as too high-cardinality a fan-out for one Organization-wide view — see that
 * class's own Javadoc), a single Account's own audit trail is exactly one {@code
 * AuditEventTargetRef}, not a fan-out at all — the exact case that class's own documented
 * limitation doesn't apply to.
 */
@FunctionalInterface
public interface GetAuditLogForAccountUseCase {

  List<AuditEvent> handle(UUID accountId);
}
