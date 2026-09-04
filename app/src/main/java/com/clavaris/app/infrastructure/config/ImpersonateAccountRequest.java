package com.clavaris.app.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * HTTP request body for {@code POST /api/v1/admin/accounts/{id}:impersonate}. {@code clientId} is
 * the {@code OAuthClient} the minted token is scoped to (must belong to the target Account's own
 * Organization — {@link ImpersonationClientNotFoundException} otherwise); {@code scopes}, when
 * omitted or empty, defaults to that client's own full {@code allowedScopes} (mirrors the max grant
 * a real interactive login of the same client could obtain).
 */
public record ImpersonateAccountRequest(@NotBlank String clientId, List<String> scopes) {}
