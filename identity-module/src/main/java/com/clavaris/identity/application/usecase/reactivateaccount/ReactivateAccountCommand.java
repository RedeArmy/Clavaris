package com.clavaris.identity.application.usecase.reactivateaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

public record ReactivateAccountCommand(AccountId accountId, AuditActor actor) {}
