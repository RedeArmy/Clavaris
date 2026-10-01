package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import com.yubico.webauthn.AssertionRequest;

/**
 * @param request the {@link AssertionRequest} {@link StartWebAuthnAuthenticationUseCase} returned
 *     moments ago — read back from the {@code HttpSession}, never trusted from the client.
 * @param credentialJson the raw JSON body of the browser's {@code navigator.credentials.get()}
 *     result.
 */
public record AuthenticateWithWebAuthnCommand(AssertionRequest request, String credentialJson) {}
