package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

import com.clavaris.clientregistry.domain.model.ClientDomainMode;
import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * {@code embeddingOrigin} is optional — {@code null} means "no iframe embedding, standalone only."
 */
public record RequestClientDomainConfigCommand(
    UUID organizationId,
    UUID oauthClientId,
    ClientDomainMode mode,
    String hostname,
    String embeddingOrigin,
    AuditActor actor) {}
