package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

import com.clavaris.common.domain.model.AuditActor;

/** Same shape as {@code rotateorganizationclientsecret.RotateOrganizationClientSecretCommand}. */
public record RotateOAuthClientSecretCommand(String clientId, AuditActor actor) {}
