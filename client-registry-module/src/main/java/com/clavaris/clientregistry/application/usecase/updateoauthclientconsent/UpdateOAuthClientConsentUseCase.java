package com.clavaris.clientregistry.application.usecase.updateoauthclientconsent;

@FunctionalInterface
public interface UpdateOAuthClientConsentUseCase {

  void handle(UpdateOAuthClientConsentCommand command);
}
