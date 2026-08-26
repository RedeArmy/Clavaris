package com.clavaris.organization.application.usecase.deleteorganization;

import java.util.UUID;

/**
 * Outbound port (BR-DATA-02/03's own organization-level equivalent) — deletes every {@code
 * OAuthClient} this Organization ever registered. Kept separate from {@link
 * OrganizationIdentityDataEraser} because it reaches a different business module
 * (client-registry-module, not identity-module) — same single-purpose-port convention {@code
 * SigningKeyProvisioner}/{@code OrganizationExistsChecker} already establish, one port per module
 * boundary crossed rather than one combined port hiding two. Implemented in {@code app} by {@code
 * OrganizationOAuthClientsEraserBridge}.
 */
@FunctionalInterface
public interface OrganizationOAuthClientsEraser {

  void eraseAllFor(UUID organizationId);
}
