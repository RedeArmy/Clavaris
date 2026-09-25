package com.clavaris.clientregistry.application.usecase.updateoauthclientscopes;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/** Same shape as {@code updateoauthclientgranttypes.UpdateOAuthClientGrantTypesCommand}. */
public record UpdateOAuthClientScopesCommand(
    String clientId, UUID organizationId, List<String> allowedScopes, AuditActor actor) {}
