package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record ReplayWebhookDeliveryCommand(UUID deliveryId, AuditActor actor) {}
