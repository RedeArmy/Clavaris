package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations/{organizationId}/clients}. No
 * {@code clientId}/{@code clientSecret} field — both are generated server-side (see {@code
 * RegisterOAuthClientService}), never accepted from the caller.
 */
public record RegisterOAuthClientRequest(
    @NotEmpty List<@NotBlank String> redirectUris,
    @NotEmpty List<@NotBlank String> allowedGrantTypes,
    @NotNull List<@NotBlank String> allowedScopes) {}
