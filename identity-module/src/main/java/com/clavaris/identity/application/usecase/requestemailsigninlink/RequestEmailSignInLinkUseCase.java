package com.clavaris.identity.application.usecase.requestemailsigninlink;

@FunctionalInterface
public interface RequestEmailSignInLinkUseCase {

  void handle(RequestEmailSignInLinkCommand command);
}
