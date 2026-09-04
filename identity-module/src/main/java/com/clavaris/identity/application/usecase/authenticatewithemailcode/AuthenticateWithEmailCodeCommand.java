package com.clavaris.identity.application.usecase.authenticatewithemailcode;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param organizationId BR-ORG-02: cross-checked against the resolved token's own account, exactly
 *     like {@code AuthenticateWithPasswordCommand}'s own organization-scoped lookup — this screen
 *     must only ever be capable of authenticating against the one Organization its own URL names.
 * @param presentedRawCode never the hash, never persisted as-is, same convention as {@code
 *     ConfirmEmailVerificationCommand}.
 */
public record AuthenticateWithEmailCodeCommand(
    OrganizationId organizationId, Email email, String presentedRawCode) {}
