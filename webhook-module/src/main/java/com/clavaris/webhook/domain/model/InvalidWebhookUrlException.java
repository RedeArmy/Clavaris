package com.clavaris.webhook.domain.model;

/**
 * TD-ARCH-058/059: a webhook endpoint URL that is not an absolute https URL with a usable port. Its
 * own type, apart from {@link IllegalArgumentException} in general, so an adapter can answer 400
 * for exactly this case without turning every other illegal argument (which would be a defect) into
 * a client error. It is still an {@code IllegalArgumentException}, so a caller that already expects
 * one keeps working.
 */
public final class InvalidWebhookUrlException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public InvalidWebhookUrlException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
