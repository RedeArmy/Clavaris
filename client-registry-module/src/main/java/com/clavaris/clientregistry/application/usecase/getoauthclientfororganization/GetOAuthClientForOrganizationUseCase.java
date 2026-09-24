package com.clavaris.clientregistry.application.usecase.getoauthclientfororganization;

import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Optional;

/**
 * Inbound port for the dashboard's own OAuth Client detail page. Empty means either the {@code
 * clientId} doesn't exist at all, or it belongs to a different Organization — collapsed into the
 * same outcome, same anti-enumeration reasoning {@code DeactivateOAuthClientService}'s own
 * identical filter already documents (never distinguishable to the caller).
 */
@FunctionalInterface
public interface GetOAuthClientForOrganizationUseCase {

  Optional<OAuthClient> handle(GetOAuthClientForOrganizationQuery query);
}
