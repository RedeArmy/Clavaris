package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import com.yubico.webauthn.AssertionRequest;

/**
 * Discoverable/resident-key ("usernameless") flow only — the browser itself, not this server,
 * decides which of the Account's registered passkeys to present, after the platform/security key
 * surfaces every credential it holds for this Relying Party. Clavaris never asks for an email/
 * username before starting this ceremony (unlike email-code/email-link sign-in), so there is
 * nothing to key a {@code StartAssertionOptions.username(...)}/{@code .userHandle(...)} off here.
 */
@FunctionalInterface
public interface StartWebAuthnAuthenticationUseCase {

  AssertionRequest handle();
}
