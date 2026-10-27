package com.clavaris.identity.application.usecase.requestemailchange;

@FunctionalInterface
public interface RequestEmailChangeUseCase {

  /**
   * Always completes the same way for a well-formed request with the right password, whether or not
   * the new address is free: telling the caller it is taken would make this form an oracle for
   * which addresses already hold an Account in the Organization (the registration-enumeration
   * concern, BR-ID-08).
   */
  void handle(RequestEmailChangeCommand command);
}
