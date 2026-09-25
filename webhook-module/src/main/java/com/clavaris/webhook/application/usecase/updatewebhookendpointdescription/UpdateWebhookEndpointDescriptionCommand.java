package com.clavaris.webhook.application.usecase.updatewebhookendpointdescription;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record UpdateWebhookEndpointDescriptionCommand(
    UUID endpointId, String description, AuditActor actor) {}
