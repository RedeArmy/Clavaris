package com.clavaris.webhook.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * HTTP request body for {@code POST
 * /api/v1/admin/organizations/{organizationId}/webhook-endpoints}.
 */
@SuppressWarnings("PMD.LongVariable")
public record RegisterWebhookEndpointRequest(
    @NotBlank String url,
    String description,
    @NotEmpty List<@NotBlank String> subscribedEventTypes) {}
