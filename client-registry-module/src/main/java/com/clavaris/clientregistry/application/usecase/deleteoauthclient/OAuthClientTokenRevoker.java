package com.clavaris.clientregistry.application.usecase.deleteoauthclient;

import java.util.UUID;

/**
 * Outbound port — deliberately does not reference Spring Authorization Server's own {@code
 * oauth2_authorization} table/types directly, same module-independence rule {@code
 * OrganizationTokenRevoker} (organization-module) already establishes for the identical problem at
 * Organization-delete scope. Implemented in {@code app}, the one module allowed to depend on both,
 * by {@code OAuthClientTokenRevokerBridge}.
 *
 * <p>{@code oauth2_authorization.registered_client_id} has no FK constraint to {@code
 * oauth_clients.id} (confirmed against the migration SQL) — deleting an {@code OAuthClient} row
 * would otherwise silently orphan any lingering authorization rows still referencing it. Must be
 * called before the {@code OAuthClient} row itself is deleted, same ordering {@code
 * OrganizationTokenRevoker}'s own Javadoc requires.
 */
@FunctionalInterface
public interface OAuthClientTokenRevoker {

  /**
   * @param oauthClientId the OAuthClient's own internal {@code id} — {@code
   *     OrganizationRegisteredClientRepository#toRegisteredClient} mints every {@code
   *     RegisteredClient} with {@code withId(client.id().toString())}, so this, not {@code
   *     clientId}, is what {@code oauth2_authorization.registered_client_id} actually stores.
   */
  void revokeAllTokensFor(UUID oauthClientId);
}
