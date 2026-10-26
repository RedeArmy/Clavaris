package com.clavaris.clientregistry.application.usecase.createorganizationclient;

import com.clavaris.clientregistry.domain.model.OrganizationClient;

/**
 * @param rawClientSecret the cleartext secret — same "returned once, at creation, never persisted
 *     or logged" convention as {@code RegisterOAuthClientResult}/{@code
 *     BootstrapPlatformClientCommand}.
 */
public record CreateOrganizationClientResult(
    OrganizationClient organizationClient, String rawClientSecret) {}
