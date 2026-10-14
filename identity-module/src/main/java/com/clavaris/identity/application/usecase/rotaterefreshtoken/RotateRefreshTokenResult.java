package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code authorizedScopes} comes back from the {@link com.clavaris.identity.domain.model.Session}'s
 * own fixed set (RFC 6749 §6) — the caller uses it to validate the requested scopes and to stamp
 * the newly-minted access token's own scope claim. {@code authenticatedAt} lets the caller stamp a
 * refreshed ID token's OIDC {@code auth_time} claim with when the user actually authenticated (the
 * same instant the original ID token carried), not when this rotation happened — RFC/OIDC Core §2's
 * own distinction between token issuance and authentication time, which a refresh must not blur.
 * {@code oidcSid} is the {@code sid} the original ID token carried (null for a Session opened
 * before it was recorded): a refreshed ID token repeats it, and the caller falls back to the
 * Session id only when there is none.
 */
public record RotateRefreshTokenResult(
    AccountId accountId,
    UUID sessionId,
    List<String> authorizedScopes,
    Instant authenticatedAt,
    String newRawToken,
    Instant newExpiresAt,
    String oidcSid) {}
