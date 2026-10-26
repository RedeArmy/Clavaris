package com.clavaris.common.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Works out which Organization an audit event belongs to from what the event is about, so {@code
 * audit_events} can carry the tenant key (TD-ARCH-038) without the roughly eighty call sites that
 * record events each having to pass it. The implementation lives in the {@code app} module because
 * the answer lives in tables owned by several modules (workspaces, webhook endpoints, accounts,
 * OAuth clients); {@code common} only states the question.
 *
 * <p>Empty means "no Organization": a platform-level target, or one that no longer exists.
 */
@FunctionalInterface
public interface AuditTenantResolver {

  Optional<UUID> organizationOf(String targetType, String targetId);
}
