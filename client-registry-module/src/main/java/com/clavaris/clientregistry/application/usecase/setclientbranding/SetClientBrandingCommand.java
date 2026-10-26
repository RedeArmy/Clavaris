package com.clavaris.clientregistry.application.usecase.setclientbranding;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/** Every field is nullable — omitted means "leave unconfigured," not "clear to blank." */
public record SetClientBrandingCommand(
    UUID organizationId,
    UUID oauthClientId,
    String logoUrl,
    String primaryColor,
    String applicationDisplayName,
    AuditActor actor) {}
