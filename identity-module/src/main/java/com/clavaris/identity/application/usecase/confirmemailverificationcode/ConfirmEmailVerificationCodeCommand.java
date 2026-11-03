package com.clavaris.identity.application.usecase.confirmemailverificationcode;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param presentedRawCode what the person typed, as typed: case and separators are normalised by
 *     the service, never by the caller
 */
public record ConfirmEmailVerificationCodeCommand(
    OrganizationId organizationId, Email email, String presentedRawCode) {}
