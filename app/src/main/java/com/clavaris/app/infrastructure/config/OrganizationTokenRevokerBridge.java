package com.clavaris.app.infrastructure.config;

import com.clavaris.organization.application.usecase.deleteorganization.OrganizationTokenRevoker;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not
 * organization-module, for the same reason {@code AccountTokenRevokerBridge}'s implementation does
 * (identity-module's own equivalent, BR-ID-04): organization-module must never depend on Spring
 * Authorization Server or its {@code oauth2_authorization} table (TD-SEC-003).
 *
 * <p>Two independent matches, not one: {@code principal_name} covers every token issued to an
 * Account within this Organization (the interactive Authorization Code flow's own access/ID tokens,
 * {@code principal_name} = the Account's own UUID — same convention {@code
 * AccountTokenRevokerBridge} already relies on); {@code registered_client_id} covers every token
 * issued <em>to</em> an OAuthClient belonging to this Organization directly (the org-tier {@code
 * client_credentials} grant, where the "principal" is the client itself, not an end-user Account —
 * {@code OrganizationOidcIssuerIntegrationTest} already proves this grant is real and used). Both
 * subqueries must run while the accounts/oauth_clients rows they reference still exist — this port
 * is called before {@code DeleteOrganizationService}'s own cascade-delete, never after.
 */
@Component
class OrganizationTokenRevokerBridge implements OrganizationTokenRevoker {

  private final JdbcTemplate jdbcTemplate;

  /* package */ OrganizationTokenRevokerBridge(final JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void revokeAllTokensFor(final UUID organizationId) {
    jdbcTemplate.update(
        "delete from oauth2_authorization where principal_name in"
            + " (select id::text from accounts where organization_id = ?)"
            + " or registered_client_id in"
            + " (select id::text from oauth_clients where organization_id = ?)",
        organizationId,
        organizationId);
  }
}
