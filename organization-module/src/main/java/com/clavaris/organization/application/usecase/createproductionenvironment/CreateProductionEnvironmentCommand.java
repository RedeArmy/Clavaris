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
 */
@SuppressWarnings("PMD.LongVariable")
public record CreateProductionEnvironmentCommand(
    UUID developmentOrganizationId, String name, AuditActor actor) {}
