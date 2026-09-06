package com.clavaris.clientregistry.application.usecase.setclientbranding;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/** Every field is nullable — omitted means "leave unconfigured," not "clear to blank." */
// PMD.LongVariable: same RedirectPolicy/OAuthClient precedent — applicationDisplayName names
// exactly what ADR-0009 §3 itself calls the field, not arbitrarily long.
@SuppressWarnings("PMD.LongVariable")
public record SetClientBrandingCommand(
    UUID organizationId,
    UUID oauthClientId,
    String logoUrl,
    String primaryColor,
    String applicationDisplayName,
    AuditActor actor) {}
