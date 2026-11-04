package com.clavaris.identity.application.usecase.resettotpforaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

public record ResetTotpForAccountCommand(AccountId accountId, AuditActor actor) {}
