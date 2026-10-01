package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;

/**
 * @param request the {@link PublicKeyCredentialCreationOptions} {@link
 *     StartWebAuthnRegistrationUseCase} returned moments ago — the controller reads it back out of
 *     the {@code HttpSession}, never trusts a client-supplied copy (the whole point of the
 *     challenge living server-side).
 * @param credentialJson the raw JSON body of the browser's {@code navigator.credentials.create()}
 *     result.
 * @param nickname optional user-supplied label (e.g. "MacBook Touch ID") — display only.
 */
public record CompleteWebAuthnRegistrationCommand(
    AccountId accountId,
    OrganizationId organizationId,
    PublicKeyCredentialCreationOptions request,
    String credentialJson,
    String nickname) {}
