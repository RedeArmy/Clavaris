package com.clavaris.clientregistry.application.usecase.deleteoauthclient;

@FunctionalInterface
public interface DeleteOAuthClientUseCase {

  void handle(DeleteOAuthClientCommand command);
}
