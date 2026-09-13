package com.clavaris.organization.application.usecase.getauditlogfororganization;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}, bridging to client-registry-module's own {@code
 * ListOAuthClientsUseCase}. Same "own module can't depend on a peer module directly, bridge in app
 * instead" convention {@code OrganizationForPlatformAccountResolver} already established, just
 * running in the opposite direction: this module reading INTO client-registry-module's owned
 * resource ids, rather than client-registry-module reading organization-module's ownership check.
 *
 * <p>Returns each {@code OAuthClient}'s own domain {@code id()} (a UUID's string form) — the exact
 * value {@code client_branding.set}/{@code client_domain_config.*}/{@code redirect_policy.set}
 * already use as their own {@code audit_events.target_id} (see each service's own {@code
 * command.oauthClientId().toString()} call), not the OAuth {@code client_id} string. Registration/
 * deactivation/rotation of the {@code OAuthClient} itself already targets {@code "Organization"}
 * directly and needs no id from this port at all — see {@code GetAuditLogForOrganizationService}'s
 * own Javadoc.
 */
@FunctionalInterface
public interface OAuthClientIdsForAuditLogProvider {

  List<String> oauthClientIds(UUID organizationId);
}
