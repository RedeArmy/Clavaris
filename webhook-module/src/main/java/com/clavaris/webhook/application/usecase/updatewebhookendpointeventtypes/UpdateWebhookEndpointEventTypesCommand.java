package com.clavaris.webhook.application.usecase.updatewebhookendpointeventtypes;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

// PMD.LongVariable: subscribedEventTypes matches WebhookEndpoint's own field/accessor name
// exactly, not arbitrarily long — same precedent RegisterOAuthClientRequest's own identical
// suppression documents for postLogoutRedirectUris.
@SuppressWarnings("PMD.LongVariable")
public record UpdateWebhookEndpointEventTypesCommand(
    UUID endpointId, List<String> subscribedEventTypes, AuditActor actor) {}
