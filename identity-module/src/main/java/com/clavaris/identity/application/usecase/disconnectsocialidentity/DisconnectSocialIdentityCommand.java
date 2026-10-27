package com.clavaris.identity.application.usecase.disconnectsocialidentity;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;

public record DisconnectSocialIdentityCommand(
    AccountId accountId, SocialProvider provider, AuditActor actor) {}
