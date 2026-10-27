package com.clavaris.identity.application.usecase.confirmemailchange;

public class InvalidEmailChangeTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidEmailChangeTokenException() {
    super("Invalid or expired email change token");
  }
}
