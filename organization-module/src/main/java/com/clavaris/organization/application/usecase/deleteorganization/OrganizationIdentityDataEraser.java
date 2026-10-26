package com.clavaris.organization.application.usecase.deleteorganization;

import java.util.UUID;

/**
 * Outbound port (BR-DATA-02/03's own organization-level equivalent) — deletes every identity-module
 * row this Organization owns: every {@code Account} (cascading, same migration as individual
 * account deletion, to each one's own {@code password_credentials}/{@code sessions}/{@code
 * refresh_tokens}/{@code verification_tokens}) and every {@code SigningKey} it ever rotated
 * through. Deliberately does NOT reference {@code Account}/{@code SigningKey} or any
 * identity-module type directly — organization-module and identity-module stay mutually independent
 * business modules, same module-graph dependency rule {@code SigningKeyProvisioner}'s own Javadoc
 * already documents for the *creation* side of this exact boundary. Implemented in {@code app}, the
 * one module allowed to depend on both, by {@code OrganizationIdentityDataEraserBridge}.
 */
@FunctionalInterface
public interface OrganizationIdentityDataEraser {

  /**
   * Deletes the database rows (inside the caller's transaction) and returns the slow side effects
   * that belong to them but must not run inside it: revoking every live session of the erased
   * accounts and purging the signing-key material from the key store (TD-PERF-043). The caller runs
   * the returned action once the transaction has committed, so a rollback leaves the key material
   * intact and the transaction never holds a connection while those are done.
   */
  Runnable eraseAllFor(UUID organizationId);
}
