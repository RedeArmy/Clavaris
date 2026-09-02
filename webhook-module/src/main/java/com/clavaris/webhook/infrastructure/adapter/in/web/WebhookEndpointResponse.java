package com.clavaris.webhook.infrastructure.adapter.in.web;

import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Never carries any signing secret — see {@link RegisterWebhookEndpointResponse}/{@link
 * RotateWebhookEndpointSecretResponse} for the two calls that legitimately return one, exactly
 * once.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public record WebhookEndpointResponse(
    UUID id,
    UUID organizationId,
    String url,
    String description,
    List<String> subscribedEventTypes,
    boolean active,
    Instant createdAt) {

  public static WebhookEndpointResponse from(final WebhookEndpoint endpoint) {
    return new WebhookEndpointResponse(
        endpoint.id(),
        endpoint.organizationId(),
        endpoint.url(),
        endpoint.description(),
        endpoint.subscribedEventTypes(),
        endpoint.active(),
        endpoint.createdAt());
  }
}
