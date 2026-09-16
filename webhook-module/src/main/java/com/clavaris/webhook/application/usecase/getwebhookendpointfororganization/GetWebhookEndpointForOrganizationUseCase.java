package com.clavaris.webhook.application.usecase.getwebhookendpointfororganization;

import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.util.Optional;

/**
 * TD-PERF-026 (SDE-III review, 2026-09-16): the O(1) "does this endpoint belong to this
 * Organization" lookup {@code
 * WebhookDashboardControllerSupport#requireEndpointBelongsToOrganization} needs before any
 * dashboard action mutates or reads anything keyed by {@code endpointId} alone — see {@code
 * WebhookEndpointRepository#findByIdAndOrganizationId}'s own Javadoc for the O(n)
 * full-organization-scan this replaces. Empty, not an exception, on a miss (unknown endpoint, or a
 * genuine cross-Organization mismatch) — the caller decides how to surface that (a 404, in every
 * current caller), same "the use case reports facts, the web layer decides the HTTP response"
 * division of responsibility {@code AccountRepository#findByOrganizationIdAndEmail}'s own callers
 * already follow.
 */
@FunctionalInterface
public interface GetWebhookEndpointForOrganizationUseCase {

  Optional<WebhookEndpoint> handle(GetWebhookEndpointForOrganizationQuery query);
}
