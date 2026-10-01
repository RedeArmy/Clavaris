package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * TD-FUT-034: the single {@code HttpSession} attribute a WebAuthn registration ceremony's own
 * in-flight challenge is carried on between {@code AccountWebAuthnCredentialsController}'s own
 * {@code /registration/start} and {@code /registration/finish} endpoints — same "plain String value
 * only" constraint {@link DeviceTrustPendingState}'s own Javadoc documents (the Redis session
 * serializer this codebase uses); the value stored is Yubico's own {@code
 * PublicKeyCredentialCreationOptions#toJson()} serialization, not a Java-serialized object.
 */
/* package */ final class WebAuthnRegistrationPendingState {

  /* package */ static final String ATTRIBUTE = "clavaris.webAuthn.pendingRegistration";

  private WebAuthnRegistrationPendingState() {
    // Constants only.
  }
}
