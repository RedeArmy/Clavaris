package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

/**
 * TD-SEC-053: thrown by {@link WebhookUrlSsrfGuard} when a webhook endpoint URL resolves to a
 * network address it must never be allowed to reach (private, loopback, link-local — including
 * cloud metadata endpoints — multicast, or unspecified), or fails to resolve at all.
 */
public final class UnsafeWebhookUrlException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnsafeWebhookUrlException(final String reason) {
    super("Webhook endpoint URL rejected: " + reason);
  }
}
