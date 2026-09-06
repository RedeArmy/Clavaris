package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

public record VerifyClientDomainOwnershipCommand(
    UUID organizationId, UUID oauthClientId, AuditActor actor) {}
