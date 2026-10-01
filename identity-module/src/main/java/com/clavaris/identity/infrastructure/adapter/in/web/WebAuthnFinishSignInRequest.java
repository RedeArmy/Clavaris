package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * @param credential the raw JSON body of the browser's {@code navigator.credentials.get()} result.
 * @param clientId Clerk "customize redirect URLs" parity — both nullable, carried from the login
 *     page's own query params the same way every other sign-in method's hidden form fields do, so
 *     {@code RedirectUrlResolver} can resolve the exact same target a password/social login would.
 */
public record WebAuthnFinishSignInRequest(String credential, String clientId, String redirectUrl) {}
