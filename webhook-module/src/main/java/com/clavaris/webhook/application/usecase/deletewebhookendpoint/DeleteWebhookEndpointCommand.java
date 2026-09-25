package com.clavaris.webhook.application.usecase.deletewebhookendpoint;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record DeleteWebhookEndpointCommand(UUID endpointId, AuditActor actor) {}
