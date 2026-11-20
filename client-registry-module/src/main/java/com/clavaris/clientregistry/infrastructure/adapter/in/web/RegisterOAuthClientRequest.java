package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.PkcePolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * @param pkcePolicy ADR-0043: optional, nullable for the same reason as {@code requireConsent}: an
 *     operator who omits it gets {@code REQUIRED} (resolved in {@link
 *     RegisterOAuthClientController}), and one whose client library cannot send PKCE must say
 *     {@code OPTIONAL} explicitly. A value other than those two is a 400.
 * @param postLogoutRedirectUris TD-FUT-018: optional — an operator who omits this field gets an
 *     empty allowlist (resolved in {@link RegisterOAuthClientController}), same behavior as before
 *     this field existed (SAS's own bare default redirect on RP-Initiated Logout).
 */
public record RegisterOAuthClientRequest(
    @NotEmpty List<@NotBlank String> redirectUris,
    @NotEmpty List<@NotBlank String> allowedGrantTypes,
    @NotNull List<@NotBlank String> allowedScopes,
    Boolean requireConsent,
    PkcePolicy pkcePolicy,
    List<@NotBlank String> postLogoutRedirectUris,
    // ADR-0049: seconds an access token lives; omitted is the default (300). The bounds are the
    // ones AccessTokenLifetime enforces.
    @Min(60) @Max(3600) Integer accessTokenTtlSeconds) {}
