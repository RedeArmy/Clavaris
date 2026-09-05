package com.clavaris.identity.application.usecase.authenticatewithemaillink;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param organizationId BR-ORG-02: cross-checked against the resolved token's own account — see
 *     {@code authenticatewithemailcode.AuthenticateWithEmailCodeCommand}'s own identical Javadoc.
 * @param presentedRawToken the value from the emailed link's query parameter, same convention as
 *     {@code ConfirmEmailVerificationCommand}.
 */
public record AuthenticateWithEmailLinkCommand(
    OrganizationId organizationId, String presentedRawToken) {}
