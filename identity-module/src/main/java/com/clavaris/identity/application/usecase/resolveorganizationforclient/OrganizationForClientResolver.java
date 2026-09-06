package com.clavaris.identity.application.usecase.resolveorganizationforclient;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;

/**
 * TD-SEC-011 (branded consent page, 2026-09-06): every other hosted page on this system's own
 * interactive flow is reached under {@code /o/{organizationId}/...} — {@code organizationId} always
 * comes from the path, never guessed at. The consent page is the one deliberate exception: Spring
 * Authorization Server's own {@code consentPage(...)} setting accepts one static, org-agnostic
 * literal path shared by every Organization on this chain (confirmed by reading {@code
 * OAuth2AuthorizationEndpointFilter#resolveConsentUri}/{@code
 * OAuth2ConfigurerUtils#withMultipleIssuersPattern} directly, not assumed) — it has no per-request
 * templating hook to receive {@code {organizationId}} at all. This port exists to resolve the
 * Organization the other way around, from the one identifier SAS's own consent redirect does carry:
 * the OAuth2 {@code client_id} it already validated before ever reaching this page.
 *
 * <p>Deliberately does not reference client-registry-module's {@code OAuthClient} type directly —
 * same module-independence rule {@link
 * com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider} already
 * follows for an identical cross-module need. Implemented in {@code app} by delegating to
 * client-registry-module's own {@code OAuthClientRepository#findByClientId}.
 */
@FunctionalInterface
public interface OrganizationForClientResolver {

  /**
   * @param clientId the OAuth2 {@code client_id} query parameter SAS's own consent redirect always
   *     carries (validated by SAS itself before this page is ever reached — an unresolvable value
   *     here means a directly-crafted request, not a real consent flow).
   * @return the Organization that owns this client, or empty for an unknown {@code client_id}.
   */
  Optional<OrganizationId> resolve(String clientId);
}
