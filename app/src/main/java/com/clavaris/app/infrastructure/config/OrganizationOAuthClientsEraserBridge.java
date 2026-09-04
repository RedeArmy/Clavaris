package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationOAuthClientsEraser;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, for the same module-graph reason {@code CreateOrganizationSigningKeyBridge}
 * does.
 *
 * <p>ADR-0023: also erases every {@code OrganizationClient} (Secret Key) this Organization ever
 * minted — both are client-registry-module's own credentials, and neither table carries a real FK
 * to {@code organizations} (cross-module migration-ordering reasoning, both tables' own migration
 * comments), so this bridge is the one place either actually gets cleaned up on hard-delete. The
 * port's own name stays {@code OrganizationOAuthClientsEraser} — narrower than what it now does —
 * rather than a rename touching {@code DeleteOrganizationService}'s wiring for a purely cosmetic
 * gain; this Javadoc is the correction.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class OrganizationOAuthClientsEraserBridge implements OrganizationOAuthClientsEraser {

  private final OAuthClientRepository oauthClients;
  private final OrganizationClientRepository organizationClients;

  /* package */ OrganizationOAuthClientsEraserBridge(
      final OAuthClientRepository oauthClients,
      final OrganizationClientRepository organizationClients) {
    this.oauthClients = oauthClients;
    this.organizationClients = organizationClients;
  }

  @Override
  public void eraseAllFor(final UUID organizationId) {
    oauthClients.deleteAllByOrganizationId(organizationId);
    organizationClients.deleteAllByOrganizationId(organizationId);
  }
}
