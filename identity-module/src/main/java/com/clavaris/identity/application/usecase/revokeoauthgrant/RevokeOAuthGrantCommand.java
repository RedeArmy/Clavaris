package com.clavaris.identity.application.usecase.revokeoauthgrant;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

public record RevokeOAuthGrantCommand(
    AccountId accountId, String authorizationId, AuditActor actor) {}
