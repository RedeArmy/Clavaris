package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

/**
 * ADR-0035: why a one-time code is being asked for after the primary factor already succeeded. Only
 * the wording of the email differs; the code, its lifetime, its single use and its lockout
 * (ADR-0033) are the same for both.
 */
public enum ChallengeReason {

  /** ADR-0024 §6: this device is not one the account has signed in from before. */
  NEW_DEVICE,

  /** ADR-0035: the Organization asks for a code after every valid password, on any device. */
  EVERY_SIGN_IN
}
