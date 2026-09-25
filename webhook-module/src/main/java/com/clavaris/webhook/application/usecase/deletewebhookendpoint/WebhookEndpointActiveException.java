package com.clavaris.webhook.application.usecase.deletewebhookendpoint;

import java.util.UUID;

/**
 * Live UX request, 2026-09-25: same "must already be deactivated before a permanent delete" guard
 * {@code client-registry-module}'s own {@code OAuthClientActiveException} already establishes for
 * an identical shape of irreversible action.
 */
public final class WebhookEndpointActiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  @SuppressWarnings("PMD.ShortVariable")
  public WebhookEndpointActiveException(final UUID id) {
    super("WebhookEndpoint " + id + " must be deactivated before it can be deleted");
  }
}
