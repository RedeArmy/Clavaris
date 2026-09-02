package com.clavaris.webhook.application.usecase.activatewebhookendpoint;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record ActivateWebhookEndpointCommand(UUID endpointId, AuditActor actor) {}
