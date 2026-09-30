package com.clavaris.identity.application.usecase.updateaccountmetadata;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Input to {@link UpdateAccountMetadataUseCase} — TD-FUT-034, Clerk "Metadata" parity. One combined
 * submit for all three tiers, same "whole-object PUT" shape {@code
 * UpdateAccountPermissionsCommand}'s own Javadoc already establishes for this Account's other
 * admin-settable fields, not three separate one-off actions.
 *
 * @param publicMetadata raw JSON text, or {@code null}/blank to clear this tier
 * @param privateMetadata raw JSON text, or {@code null}/blank to clear this tier
 * @param unsafeMetadata raw JSON text, or {@code null}/blank to clear this tier
 */
public record UpdateAccountMetadataCommand(
    AccountId accountId,
    String publicMetadata,
    String privateMetadata,
    String unsafeMetadata,
    AuditActor actor) {}
