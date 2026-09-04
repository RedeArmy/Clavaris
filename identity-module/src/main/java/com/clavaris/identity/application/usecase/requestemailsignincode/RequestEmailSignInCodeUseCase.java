package com.clavaris.identity.application.usecase.requestemailsignincode;

@FunctionalInterface
public interface RequestEmailSignInCodeUseCase {

  void handle(RequestEmailSignInCodeCommand command);
}
