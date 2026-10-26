package com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

public record UpdateWebhookEndpointEventTypesCommand(
    UUID endpointId, List<String> subscribedEventTypes, AuditActor actor) {}
