package com.clavaris.organization.application.usecase.createproductionenvironment;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * @param developmentOrganizationId the existing {@code DEVELOPMENT} Organization being promoted —
 *     resolved from the path, never caller-chosen data
 * @param name display name for the new {@code PRODUCTION} sibling — deliberately not defaulted from
 *     the source Organization's own name (an operator may want "JobSeeker" vs. "JobSeeker
 *     (production)" or an entirely different name; forcing an explicit choice here is cheaper than
 *     a later rename)
 * @param actor either {@link AuditActor#platformClient} (the REST admin API) or {@link
 *     AuditActor#platformAccount} (the dashboard's own self-service Danger Zone,
 *     TD-FUT-032/2026-09-13) — this command never carried an operator-only restriction to begin
 *     with, so nothing needed correcting here, only documenting, same posture {@code
 *     RegisterWebhookEndpointCommand}'s own Javadoc already establishes for an identical situation.
 */
@SuppressWarnings("PMD.LongVariable")
public record CreateProductionEnvironmentCommand(
    UUID developmentOrganizationId, String name, AuditActor actor) {}
