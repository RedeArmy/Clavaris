package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("PMD.LongVariable")
public record RegisterWebhookEndpointCommand(
    UUID organizationId,
    String url,
    String description,
    List<String> subscribedEventTypes,
    AuditActor actor) {}
