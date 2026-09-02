package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

@FunctionalInterface
public interface PurgeSigningKeyForOrganizationUseCase {

  PurgeSigningKeyForOrganizationResult handle(PurgeSigningKeyForOrganizationCommand command);
}
