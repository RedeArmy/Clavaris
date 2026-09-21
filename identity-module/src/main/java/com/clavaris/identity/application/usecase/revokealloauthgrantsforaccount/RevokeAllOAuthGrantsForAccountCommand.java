package com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

public record RevokeAllOAuthGrantsForAccountCommand(AccountId accountId, AuditActor actor) {}
