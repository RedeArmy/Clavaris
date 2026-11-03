package com.clavaris.identity.application.usecase.confirmemailverificationcode;

/**
 * The code does not verify this address: wrong, expired, already used, or the address is unknown.
 */
public class InvalidVerificationCodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidVerificationCodeException() {
    super("Invalid or expired verification code");
  }
}
