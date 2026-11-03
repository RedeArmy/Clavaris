package com.clavaris.identity.application.usecase.resendemailverification;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param rawIdentifier what was typed: an email or, where the Organization allows it, a username
 *     (ADR-0037's rule). Never trusted to name an account until the service has resolved it.
 */
public record ResendEmailVerificationCommand(OrganizationId organizationId, String rawIdentifier) {}
