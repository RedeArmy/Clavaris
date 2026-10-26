package com.clavaris.clientregistry.infrastructure.adapter.in.web;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/branding}. Every field is
 * nullable — an omitted field means "leave unconfigured," never "clear to blank."
 */
public record SetClientBrandingRequest(
    String logoUrl, String primaryColor, String applicationDisplayName) {}
