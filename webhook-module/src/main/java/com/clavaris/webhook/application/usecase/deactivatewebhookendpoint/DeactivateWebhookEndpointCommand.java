package com.clavaris.webhook.application.usecase.deactivatewebhookendpoint;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record DeactivateWebhookEndpointCommand(UUID endpointId, AuditActor actor) {}
