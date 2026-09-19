package com.clavaris.identity.application.usecase.unbanaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

public record UnbanAccountCommand(AccountId accountId, AuditActor actor) {}
