package com.clavaris.identity.application.usecase.listoauthgrantsforaccount;

import java.time.Instant;

/**
 * Clerk "OAuth" tab parity (ADR-0026) — one row of {@code oauth2_authorization} (TD-SEC-003)
 * projected for display, joined against {@code client-registry-module}'s own {@code OAuthClient}
 * for {@code clientId}. {@code authorizationId} is that table's own primary key — the value {@link
 * com.clavaris.identity.application.usecase.revokeoauthgrant.RevokeOAuthGrantCommand} revokes by.
 * {@code clientId} is {@code null} when the registering {@code OAuthClient} no longer exists (rare:
 * deleted after issuing tokens still within their lifetime) — the template renders a fallback label
 * for that case rather than a broken join.
 */
public record OAuthGrant(
    String authorizationId,
    String clientId,
    String grantType,
    String scopes,
    Instant lastIssuedAt,
    Instant expiresAt) {}
