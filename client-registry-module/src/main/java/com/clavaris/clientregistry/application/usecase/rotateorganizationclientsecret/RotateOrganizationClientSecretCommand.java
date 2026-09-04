package com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret;

import com.clavaris.common.domain.model.AuditActor;

public record RotateOrganizationClientSecretCommand(String clientId, AuditActor actor) {}
