package com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy;

import com.clavaris.clientregistry.domain.model.PkcePolicy;
import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/** ADR-0043. Same shape as {@code updateoauthclientconsent.UpdateOAuthClientConsentCommand}. */
public record UpdateOAuthClientPkcePolicyCommand(
    String clientId, UUID organizationId, PkcePolicy pkcePolicy, AuditActor actor) {}
