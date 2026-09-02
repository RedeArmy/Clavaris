package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import java.util.UUID;

public final class WebhookDeliveryNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  @SuppressWarnings("PMD.ShortVariable")
  public WebhookDeliveryNotFoundException(final UUID id) {
    super("No WebhookDelivery exists with id " + id);
  }
}
