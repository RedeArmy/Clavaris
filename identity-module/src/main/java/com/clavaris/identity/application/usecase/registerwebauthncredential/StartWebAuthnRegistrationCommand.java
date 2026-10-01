package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param email used only as the WebAuthn ceremony's own display name — never persisted here.
 */
public record StartWebAuthnRegistrationCommand(AccountId accountId, String email) {}
