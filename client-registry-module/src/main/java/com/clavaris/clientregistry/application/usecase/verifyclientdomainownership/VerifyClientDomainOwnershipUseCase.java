package com.clavaris.clientregistry.application.usecase.verifyclientdomainownership;

@FunctionalInterface
public interface VerifyClientDomainOwnershipUseCase {

  VerifyClientDomainOwnershipResult handle(VerifyClientDomainOwnershipCommand command);
}
