package com.clavaris.organization.application.usecase.createorganization;

import java.util.UUID;

/**
 * Outbound port — deliberately does not reference identity-module's {@code PlatformAccount} type or
 * repository directly (the module-independence rule applied at the module-graph level, same
 * convention client-registry-module's own {@code OrganizationExistsChecker} follows for the
 * opposite direction). Implemented in {@code app}, the one module allowed to depend on both, by
 * delegating to identity-module's own {@code PlatformAccountRepository.findById}.
 *
 * <p>Security finding (SDE-III review, 2026-08-22): before this port existed, {@code
 * ownerPlatformAccountId} was never checked against a real {@code PlatformAccount} anywhere in the
 * creation path — the migration's own comment claimed "enforced at the application layer only," but
 * that layer didn't exist. This is that layer.
 */
@FunctionalInterface
public interface PlatformAccountExistsChecker {

  boolean exists(UUID platformAccountId);
}
