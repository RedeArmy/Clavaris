package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations/{organizationId}/clients}. No
 * {@code clientId}/{@code clientSecret} field — both are generated server-side (see {@code
 * RegisterOAuthClientService}), never accepted from the caller.
 *
 * @param requireConsent TD-SEC-026/ADR-0017: optional, deliberately nullable rather than a
 *     primitive — an operator who omits this field gets the secure default ({@code true}, resolved
 *     in {@link RegisterOAuthClientController}), while one who wants a trusted first-party client
 *     to skip the consent screen must explicitly send {@code false}, never inherit it by omission.
 * @param postLogoutRedirectUris TD-FUT-018: optional — an operator who omits this field gets an
 *     empty allowlist (resolved in {@link RegisterOAuthClientController}), same behavior as before
 *     this field existed (SAS's own bare default redirect on RP-Initiated Logout).
 */
// PMD.LongVariable: postLogoutRedirectUris is the exact OIDC spec term, not arbitrarily long —
// same precedent as OAuthClient's own identical suppression.
@SuppressWarnings("PMD.LongVariable")
public record RegisterOAuthClientRequest(
    @NotEmpty List<@NotBlank String> redirectUris,
    @NotEmpty List<@NotBlank String> allowedGrantTypes,
    @NotNull List<@NotBlank String> allowedScopes,
    Boolean requireConsent,
    List<@NotBlank String> postLogoutRedirectUris) {}
