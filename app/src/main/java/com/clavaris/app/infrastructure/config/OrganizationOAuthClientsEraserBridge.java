package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationOAuthClientsEraser;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, for the same module-graph reason {@code CreateOrganizationSigningKeyBridge}
 * does.
 */
@Component
class OrganizationOAuthClientsEraserBridge implements OrganizationOAuthClientsEraser {

  private final OAuthClientRepository oauthClients;

  /* package */ OrganizationOAuthClientsEraserBridge(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public void eraseAllFor(final UUID organizationId) {
    oauthClients.deleteAllByOrganizationId(organizationId);
  }
}
