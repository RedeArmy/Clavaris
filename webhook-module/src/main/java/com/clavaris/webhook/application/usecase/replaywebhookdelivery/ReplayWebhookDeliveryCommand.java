package com.clavaris.webhook.application.usecase.replaywebhookdelivery;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * {@code endpointId} (SDE-III review, 2026-09-03 — real bug found and closed): the path segment the
 * web adapter's own URL shape names but, before this fix, never actually checked — a {@code
 * deliveryId} belonging to a different endpoint entirely still replayed. {@link
 * ReplayWebhookDeliveryService} now verifies the delivery it loads by {@code deliveryId} actually
 * belongs to this {@code endpointId}, 404-ing (not a silent mismatch) otherwise — see that class's
 * own Javadoc for why a mismatch is treated identically to "no such delivery," never a
 * differentiated response.
 */
public record ReplayWebhookDeliveryCommand(UUID endpointId, UUID deliveryId, AuditActor actor) {}
