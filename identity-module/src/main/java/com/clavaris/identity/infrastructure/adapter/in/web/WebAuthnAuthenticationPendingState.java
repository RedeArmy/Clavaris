package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * TD-FUT-034: same shape as {@link WebAuthnRegistrationPendingState}, for the authentication
 * (sign-in) ceremony's own {@code AssertionRequest#toJson()} serialization — {@code
 * WebAuthnSignInController} is the sole reader/writer.
 */
/* package */ final class WebAuthnAuthenticationPendingState {

  /* package */ static final String ATTRIBUTE = "clavaris.webAuthn.pendingAuthentication";

  private WebAuthnAuthenticationPendingState() {
    // Constants only.
  }
}
