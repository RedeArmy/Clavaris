package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

@FunctionalInterface
public interface RequestClientDomainConfigUseCase {

  RequestClientDomainConfigResult handle(RequestClientDomainConfigCommand command);
}
