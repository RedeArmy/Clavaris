package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;

/**
 * Returns Yubico's own {@link PublicKeyCredentialCreationOptions} directly — this is the actual
 * JSON contract the browser's {@code navigator.credentials.create()} needs, so wrapping it in a
 * Clavaris-owned DTO would only duplicate WebAuthn's own well-defined shape for no benefit; same
 * "build the product on a vetted library, don't re-derive its own protocol shapes" posture ADR-0001
 * already establishes for Spring Authorization Server's own token/discovery response types.
 */
@FunctionalInterface
public interface StartWebAuthnRegistrationUseCase {

  PublicKeyCredentialCreationOptions handle(StartWebAuthnRegistrationCommand command);
}
