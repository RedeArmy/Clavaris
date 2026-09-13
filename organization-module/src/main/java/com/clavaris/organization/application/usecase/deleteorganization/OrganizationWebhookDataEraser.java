package com.clavaris.organization.application.usecase.deleteorganization;

import java.util.UUID;

/**
 * Outbound port (BR-DATA-02/03's own organization-level equivalent) — deletes every {@code
 * WebhookEndpoint}/{@code WebhookDelivery} row this Organization ever owned (ADR-0007). Same
 * single-purpose-port convention {@link OrganizationOAuthClientsEraser} already establishes, one
 * port per module boundary crossed — webhook-module has no FK relationship to {@code organizations}
 * at all (that table's own migration comment: a deliberate cross-module boundary), so without this
 * port a deleted Organization's own webhook data would silently survive as orphaned rows,
 * referencing an {@code organization_id} that no longer resolves to anything.
 *
 * <p>SDE-III review, 2026-09-13 — real gap found and closed: {@link DeleteOrganizationService}
 * erased identity-module and client-registry-module data on delete from day one, but never
 * webhook-module's, even after that module shipped (2026-09-02). Implemented in {@code app} by
 * {@code OrganizationWebhookDataEraserBridge}.
 */
@FunctionalInterface
public interface OrganizationWebhookDataEraser {
  void eraseAllFor(UUID organizationId);
}
