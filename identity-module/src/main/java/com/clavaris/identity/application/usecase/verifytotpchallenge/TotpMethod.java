package com.clavaris.identity.application.usecase.verifytotpchallenge;

/** What a correct second-factor entry turned out to be. */
public enum TotpMethod {
  /** A code from the authenticator app. */
  AUTHENTICATOR,
  /** A single-use recovery code. */
  RECOVERY_CODE
}
