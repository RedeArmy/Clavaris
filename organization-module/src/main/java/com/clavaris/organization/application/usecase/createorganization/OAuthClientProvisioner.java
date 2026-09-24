package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * Outbound port (BR-ORG-06, SDE-III refactor 2026-09-23): registers the new Organization's default
 * {@code OAuthClient}, synchronously, in the same operation as its creation — an Organization owner
 * should never have to understand OAuth2 grant types/scopes just to get a working client, the same
 * "never an observable half-provisioned state" reasoning {@link SigningKeyProvisioner} already
 * applies to the signing key. Deliberately does NOT reference {@code OAuthClient} or any
 * client-registry-module type directly — organization-module and client-registry-module stay
 * mutually independent business modules (the hexagonal dependency rule applied at the module-graph
 * level, same convention {@link SigningKeyProvisioner} follows on the identity-module side).
 * Implemented in {@code app}, the one module allowed to depend on both, by {@code
 * CreateOrganizationOAuthClientBridge}.
 *
 * <p>Registers with no redirect URI configured yet (client-registry-module's own {@code
 * OAuthClient} domain model now allows this — see its own constructor comment) — the owning
 * PlatformAccount hasn't typed one at Organization-creation time, and forcing a placeholder would
 * be worse than a real "not configured yet" state. {@code authorization_code} requests against this
 * client simply fail closed (no registered redirect URI to match) until the owner adds one via the
 * dashboard's own "OAuth Clients" page.
 */
@FunctionalInterface
public interface OAuthClientProvisioner {

  ProvisionedOAuthClient provisionFor(UUID organizationId, AuditActor actor);

  /**
   * Just enough of the provisioned client's metadata to echo back in {@code CreateOrganization}'s
   * own response. PMD's ShortVariable rule flags {@code id} for the same record-style-accessor
   * reason {@code Account} suppresses it elsewhere in this codebase.
   */
  @SuppressWarnings("PMD.ShortVariable")
  record ProvisionedOAuthClient(UUID id, String clientId) {}
}
