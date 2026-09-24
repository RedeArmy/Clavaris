package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.deleteoauthclient.OAuthClientTokenRevoker;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Implements client-registry-module's outbound port — the bridge lives in {@code app}, not
 * client-registry-module, same reason {@code OrganizationTokenRevokerBridge}'s implementation does
 * (organization-module's own equivalent for the Organization-delete cascade): client-registry-
 * module must never depend on Spring Authorization Server or its {@code oauth2_authorization}
 * table.
 *
 * <p>Single match, not two: unlike {@code OrganizationTokenRevokerBridge} (which also matches
 * {@code principal_name} for every Account-issued token across an entire Organization), this port
 * is scoped to one {@code OAuthClient}, so only {@code registered_client_id} is relevant — an
 * Account's own end-user tokens are keyed by {@code principal_name}, not by which client they were
 * issued through, and deleting one client must never revoke a still-active Account's sessions
 * issued through a different client entirely.
 */
@Component
class OAuthClientTokenRevokerBridge implements OAuthClientTokenRevoker {

  private final JdbcTemplate jdbcTemplate;

  /* package */ OAuthClientTokenRevokerBridge(final JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void revokeAllTokensFor(final UUID oauthClientId) {
    jdbcTemplate.update(
        "delete from oauth2_authorization where registered_client_id = ?",
        oauthClientId.toString());
  }
}
