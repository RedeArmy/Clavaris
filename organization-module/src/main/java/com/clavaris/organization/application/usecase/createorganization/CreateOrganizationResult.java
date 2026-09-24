package com.clavaris.organization.application.usecase.createorganization;

import com.clavaris.organization.domain.model.Organization;

/**
 * The created Organization plus its synchronously-provisioned initial signing key AND default
 * OAuthClient (BR-ORG-06) — everything an operator needs to confirm the Organization can already
 * issue a token, and to a real, already-registered client.
 */
public record CreateOrganizationResult(
    Organization organization,
    SigningKeyProvisioner.ProvisionedSigningKey signingKey,
    OAuthClientProvisioner.ProvisionedOAuthClient oauthClient) {}
