package com.clavaris.app.infrastructure.config;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@link AdaptiveSameSiteSessionCookieSerializer} needs to find the tenant of a request whose
 * path carries no {@code /o/{organizationId}} (the consent page, whose URL is fixed by Spring
 * Authorization Server).
 */
@FunctionalInterface
interface FlatPathSessionLookup {

  /** The Organization that owns this OAuth client, if the client exists. */
  Optional<UUID> organizationForClient(String clientId);
}
