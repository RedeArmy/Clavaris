package com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/** ADR-0049: {@code accessTokenTtlSeconds} null puts the client back on the default lifetime. */
public record UpdateOAuthClientAccessTokenTtlCommand(
    String clientId, UUID organizationId, Integer accessTokenTtlSeconds, AuditActor actor) {}
