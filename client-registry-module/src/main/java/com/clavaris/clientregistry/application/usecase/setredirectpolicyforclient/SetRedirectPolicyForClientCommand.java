package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/** Every URL field is nullable — omitted means "leave unconfigured," not "clear to blank." */
public record SetRedirectPolicyForClientCommand(
    UUID organizationId,
    UUID oauthClientId,
    String fallbackSignInRedirectUrl,
    String fallbackSignUpRedirectUrl,
    String forceSignInRedirectUrl,
    String forceSignUpRedirectUrl,
    AuditActor actor) {}
