package com.clavaris.identity.application.usecase.resolveclientbranding;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * ADR-0009 §3: the hosted login page's own read access to a client's theming — logo, primary color,
 * application display name. Deliberately does not reference client-registry-module's {@code
 * OAuthClient}/{@code ClientBranding} types directly — same module-independence rule {@link
 * com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver} already follows
 * for an identical cross-module need. Implemented in {@code app} by delegating to
 * client-registry-module's own {@code OAuthClientRepository}/{@code GetClientBrandingUseCase}.
 */
@FunctionalInterface
public interface ClientBrandingProvider {

  /**
   * @param organizationId the path's own Organization — a {@code clientId} that resolves to a
   *     different Organization is treated exactly like an unknown one (unconfigured), same
   *     BR-ORG-02 cross-tenant defence-in-depth {@code RedirectUrlResolver} already applies.
   * @param clientId the OAuth2 {@code client_id}, or {@code null} when the current request carries
   *     no client context at all — always {@link ClientBrandingSnapshot#unconfigured()} then.
   * @return never {@code null} — an unknown/cross-tenant/absent client's own default look.
   */
  ClientBrandingSnapshot brandingFor(OrganizationId organizationId, String clientId);
}
