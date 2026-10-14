package com.clavaris.app.infrastructure.config;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@link AdaptiveSameSiteSessionCookieSerializer} needs to find the tenant of a request whose
 * path carries no {@code /o/{organizationId}} (the consent page and the social-login endpoints,
 * whose URLs are fixed by Spring Authorization Server and Spring Security).
 */
interface FlatPathSessionLookup {

  /** The Organization that owns this OAuth client, if the client exists. */
  Optional<UUID> organizationForClient(String clientId);

  /** Whether this session started a social-login round trip that has not finished yet. */
  boolean hasSocialLoginInProgress(String sessionId);
}
