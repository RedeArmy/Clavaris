package com.clavaris.identity.infrastructure.adapter.out.mail;

/**
 * Resend's HTTP API returned a non-2xx response, or the call itself failed (network/timeout). Left
 * unchecked deliberately — {@code MailSender} (the port) declares no {@code throws}, so a failure
 * here surfaces to the calling use case exactly like any other unexpected runtime failure would,
 * consistent with {@code RequestEmailVerificationService}'s own documented "the token stays valid
 * either way, the request can be retried" stance.
 */
public final class MailDeliveryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MailDeliveryException(final String message) {
    super(message);
  }

  public MailDeliveryException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
