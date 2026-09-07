package com.clavaris.identity.infrastructure.adapter.out.security;

import java.security.KeyPair;

/**
 * TD-PERF-014: what {@link OrganizationSigningKeyMaterialFactory}'s own cache actually holds per
 * Organization — the active {@code kid} alongside its {@link KeyPair}, not the {@link KeyPair}
 * alone. Folding {@code kid} into the same cache entry is what lets {@code
 * OrganizationScopedJwkSource} stop re-querying Postgres for it on every single token issuance —
 * see that class's own Javadoc.
 */
public record ActiveSigningKey(String kid, KeyPair keyPair) {}
