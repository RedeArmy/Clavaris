package com.clavaris.identity.application.usecase.requestemailverification;

/**
 * Resend's HTTP API returned a non-2xx response, or the call itself failed (network/timeout). Lives
 * here, alongside {@link MailSender} (the port), not in {@code infrastructure/adapter/out /mail}
 * where {@code ResendMailSender} (the one adapter that actually throws it) lives — an outbound
 * port's own failure contract belongs with the port, per this codebase's own dependency rule
 * (application depends on nothing in infrastructure); {@code ResendMailSender} depending on this
 * package is the allowed direction (infrastructure depends on application), the reverse would not
 * be.
 *
 * <p>Moved here (2026-08-31) specifically because {@code recordaccountlogindevice
 * .RecordAccountLoginDeviceService} needs to catch this type by name — every other caller still
 * just lets it propagate unchecked, consistent with {@code RequestEmailVerificationService}'s own
 * documented "the token stays valid either way, the request can be retried" stance; {@code
 * RecordAccountLoginDeviceService}'s own Javadoc explains why it's the one exception to that.
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
