package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config}. {@code
 * mode}/{@code hostname} are required — unlike {@code SetClientBrandingRequest}, there is no "leave
 * unconfigured" partial-update shape for those two, since they always change together (a hostname
 * without a mode, or vice versa, is meaningless). {@code embeddingOrigin} is optional — omitted or
 * {@code null} means "no iframe embedding, standalone hosted login only."
 */
public record RequestClientDomainConfigRequest(
    @NotNull ClientDomainMode mode, @NotBlank String hostname, String embeddingOrigin) {}
