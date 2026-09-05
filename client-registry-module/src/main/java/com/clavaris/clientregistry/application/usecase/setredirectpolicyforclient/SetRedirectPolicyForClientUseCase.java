package com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient;

@FunctionalInterface
public interface SetRedirectPolicyForClientUseCase {

  SetRedirectPolicyForClientResult handle(SetRedirectPolicyForClientCommand command);
}
