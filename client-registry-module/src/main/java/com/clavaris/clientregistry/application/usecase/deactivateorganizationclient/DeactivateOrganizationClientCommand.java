package com.clavaris.clientregistry.application.usecase.deactivateorganizationclient;

import com.clavaris.common.domain.model.AuditActor;

public record DeactivateOrganizationClientCommand(String clientId, AuditActor actor) {}
