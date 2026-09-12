package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0025 (SDE-III review, 2026-09-12): {@code actor} is either an {@link
 * AuditActor#platformClient} (the REST admin API's own caller) or an {@link
 * AuditActor#platformAccount} (the dashboard's own session-authenticated caller, self-service
 * webhook registration for an Organization the PlatformAccount owns) — same widening already
 * applied to {@code CreateOrganizationClientCommand}/ {@code RegisterOAuthClientCommand}, unlike
 * those this command never carried an operator-only restriction to begin with, so there's nothing
 * to correct here, only to document. The dashboard's own ownership check happens before this
 * command is ever built, not inside this use case.
 */
@SuppressWarnings("PMD.LongVariable")
public record RegisterWebhookEndpointCommand(
    UUID organizationId,
    String url,
    String description,
    List<String> subscribedEventTypes,
    AuditActor actor) {}
