package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

/** Inbound port. */
@FunctionalInterface
public interface RotateSigningKeyForOrganizationUseCase {

  RotateSigningKeyForOrganizationResult handle(RotateSigningKeyForOrganizationCommand command);
}
