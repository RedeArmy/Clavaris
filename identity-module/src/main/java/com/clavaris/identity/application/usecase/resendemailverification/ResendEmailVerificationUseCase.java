package com.clavaris.identity.application.usecase.resendemailverification;

/**
 * Inbound port: asks for another verification email (link, code or both, as the Organization's
 * policy says) for the Account an identifier names (ADR-0038, BR-ID-28).
 */
@FunctionalInterface
public interface ResendEmailVerificationUseCase {

  /**
   * Returns normally whatever the identifier names, or does not: an unknown address, a malformed
   * value, an Account that is already verified or is not active, a request inside the wait, and a
   * real send all look the same to the caller, so the answer reveals nothing about any Account.
   */
  void handle(ResendEmailVerificationCommand command);
}
