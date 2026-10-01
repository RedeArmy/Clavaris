package com.clavaris.identity.application.usecase.approveaccountregistration;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * TD-FUT-019: approves a {@code PENDING_APPROVAL} self-registration. {@link
 * ApproveAccountRegistrationController} (admin API) always constructs this with a {@link
 * AuditActor#platformClient} actor — same tier as every other {@code /api/v1/admin/**} mutation
 * (see {@code SuspendAccountCommand}'s own identical rationale) — and this single actor shape
 * covers both real callers: an operator acting from the Clavaris admin dashboard, and a consuming
 * application's own backend acting via its tenant-scoped {@code OrganizationClient} ("Secret Key")
 * through {@code OrganizationClientOwnershipFilter}'s allowlisted route for this endpoint. Neither
 * caller is distinguishable from the other at this layer, same as every other admin-API mutation in
 * this codebase — see {@code AuditActor}'s own Javadoc for why no third actor type was added just
 * for this distinction.
 */
public record ApproveAccountRegistrationCommand(AccountId accountId, AuditActor actor) {}
