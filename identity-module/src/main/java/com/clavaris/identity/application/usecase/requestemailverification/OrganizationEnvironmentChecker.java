package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): outbound
 * port — deliberately does not reference organization-module's {@code Organization}/{@code
 * OrganizationEnvironment} types directly, same module-independence rule this module's own {@code
 * requestemailverification}/{@code requestpasswordreset} ports already follow for every other
 * cross-module concern. Implemented in {@code app} by delegating to organization-module's own
 * {@code OrganizationRepository.findById(...).environment()} — same pattern as
 * client-registry-module's identically-named, deliberately separate port in its own package (module
 * independence: neither business module may depend on the other's port type, even though both
 * declare the same method shape).
 *
 * <p>Parked here, not under {@code requestpasswordreset}: {@code RequestEmailVerificationService}
 * is this port's first consumer; {@code RequestPasswordResetService} is the second, same "one port,
 * several use cases" precedent {@link MailSender}/{@link VerificationTokenRepository} already
 * establish for this exact pair of services.
 */
@FunctionalInterface
public interface OrganizationEnvironmentChecker {

  boolean isDevelopment(OrganizationId organizationId);
}
