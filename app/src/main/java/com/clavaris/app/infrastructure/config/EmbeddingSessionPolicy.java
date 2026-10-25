package com.clavaris.app.infrastructure.config;

import java.util.UUID;

/**
 * Whether an Organization's session cookie has to be sent on cross-site requests at all, which is
 * the only reason {@code SameSite=None} exists: a page of that Organization shown inside a
 * consuming application's iframe (ADR-0009) is a third-party context, and a browser withholds
 * {@code Lax} cookies from it.
 *
 * <p>Most Organizations are never embedded, and for them {@code None} would only remove the defence
 * {@code Lax} gives a session cookie against cross-site requests. See {@link
 * AdaptiveSameSiteSessionCookieSerializer} for how the answer is used.
 */
@FunctionalInterface
interface EmbeddingSessionPolicy {

  /** True when pages of this Organization can legitimately be framed by a consuming application. */
  boolean allowsCrossSiteSession(UUID organizationId);
}
