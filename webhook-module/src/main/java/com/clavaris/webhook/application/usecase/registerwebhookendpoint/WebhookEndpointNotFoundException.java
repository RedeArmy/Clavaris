package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import java.util.UUID;

public final class WebhookEndpointNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  @SuppressWarnings("PMD.ShortVariable")
  public WebhookEndpointNotFoundException(final UUID id) {
    super("No WebhookEndpoint exists with id " + id);
  }
}
