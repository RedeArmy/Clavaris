package com.clavaris.organization.application.usecase.setorganizationsocialcredential;

import com.clavaris.organization.domain.model.OrganizationSocialCredential;

/**
 * Deliberately does not expose {@code clientSecretEncrypted} at all — the raw secret is never
 * Clavaris-generated (it's the operator's own Google/GitHub app secret), so unlike {@code
 * RegisterOAuthClientResult}'s own "return the raw secret once, at creation" convention, there is
 * no onboarding moment that needs it echoed back; the operator already has it, having just typed it
 * in.
 */
public record SetOrganizationSocialCredentialResult(OrganizationSocialCredential credential) {}
