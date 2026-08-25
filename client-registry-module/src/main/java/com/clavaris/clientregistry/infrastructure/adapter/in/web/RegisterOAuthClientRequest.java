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
 */
public record RegisterOAuthClientRequest(
    @NotEmpty List<@NotBlank String> redirectUris,
    @NotEmpty List<@NotBlank String> allowedGrantTypes,
    @NotNull List<@NotBlank String> allowedScopes,
    Boolean requireConsent) {}
