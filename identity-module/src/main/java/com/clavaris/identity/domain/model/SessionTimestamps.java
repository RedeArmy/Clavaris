package com.clavaris.identity.domain.model;

import java.time.Instant;

/**
 * The three instants a {@link Session} records: when it was opened, when the End-User actually
 * authenticated (OIDC Core §2, reported as {@code auth_time}) and when it was last seen. Grouped so
 * the aggregate and its persistence form take a handful of arguments, not one per instant.
 */
public record SessionTimestamps(Instant createdAt, Instant authenticatedAt, Instant lastSeenAt) {}
