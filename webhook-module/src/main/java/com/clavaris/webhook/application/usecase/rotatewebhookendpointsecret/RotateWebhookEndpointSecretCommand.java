package com.clavaris.webhook.application.usecase.rotatewebhookendpointsecret;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record RotateWebhookEndpointSecretCommand(UUID endpointId, AuditActor actor) {}
