package com.clavaris.identity.application.usecase.chooseaccountusername;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * ADR-0031: the username a person picks right after a social sign-up that gave them none.
 *
 * @param rawUsername what was typed; {@code Username} normalizes it and rejects a bad shape
 */
public record ChooseAccountUsernameCommand(
    AccountId accountId, String rawUsername, AuditActor actor) {}
