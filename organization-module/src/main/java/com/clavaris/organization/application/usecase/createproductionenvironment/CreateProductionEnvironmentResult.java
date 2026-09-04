package com.clavaris.organization.application.usecase.createproductionenvironment;

import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.domain.model.Organization;

/**
 * The newly created {@code PRODUCTION} Organization plus its synchronously-provisioned signing key
 * — same shape as {@code CreateOrganizationResult}, since this reuses the exact same "an
 * Organization that exists but cannot yet issue a token is never an observable state" guarantee
 * (BR-ORG-06).
 */
public record CreateProductionEnvironmentResult(
    Organization organization, SigningKeyProvisioner.ProvisionedSigningKey signingKey) {}
