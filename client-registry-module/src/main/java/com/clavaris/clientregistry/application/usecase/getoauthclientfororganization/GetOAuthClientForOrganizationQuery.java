package com.clavaris.clientregistry.application.usecase.getoauthclientfororganization;

import java.util.UUID;

/**
 * Read query backing the dashboard's own per-client detail page — same {@code clientId} + {@code
 * organizationId} ownership-verification shape as {@code
 * deactivateoauthclient.DeactivateOAuthClientCommand}, just a query, not a mutation.
 */
public record GetOAuthClientForOrganizationQuery(String clientId, UUID organizationId) {}
