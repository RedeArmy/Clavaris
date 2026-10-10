package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param organizationId the Organization the Account belongs to: its name is what the browser shows
 *     when it asks to save the passkey
 * @param email used only as the WebAuthn ceremony's own display name — never persisted here.
 */
public record StartWebAuthnRegistrationCommand(
    AccountId accountId, OrganizationId organizationId, String email) {}
