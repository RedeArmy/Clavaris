package com.clavaris.webhook.application.usecase.updatewebhookendpointurl;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record UpdateWebhookEndpointUrlCommand(UUID endpointId, String url, AuditActor actor) {}
