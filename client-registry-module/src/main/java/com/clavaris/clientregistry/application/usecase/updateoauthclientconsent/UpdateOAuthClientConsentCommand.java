package com.clavaris.clientregistry.application.usecase.updateoauthclientconsent;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/** Same shape as {@code updateoauthclientgranttypes.UpdateOAuthClientGrantTypesCommand}. */
public record UpdateOAuthClientConsentCommand(
    String clientId, UUID organizationId, boolean requireConsent, AuditActor actor) {}
