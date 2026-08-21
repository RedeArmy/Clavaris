package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code authorizedScopes} comes back from the {@link com.clavaris.identity.domain.model.Session}'s
 * own fixed set (RFC 6749 §6) — the caller uses it to validate the requested scopes and to stamp
 * the newly-minted access token's own scope claim. {@code sessionCreatedAt} lets the caller stamp a
 * refreshed ID token's OIDC {@code auth_time} claim with when the user actually authenticated, not
 * when this particular rotation happened — RFC/OIDC Core §2's own distinction between token
 * issuance and authentication time, which a refresh must not blur.
 */
public record RotateRefreshTokenResult(
    AccountId accountId,
    UUID sessionId,
    List<String> authorizedScopes,
    Instant sessionCreatedAt,
    String newRawToken,
    Instant newExpiresAt) {}
