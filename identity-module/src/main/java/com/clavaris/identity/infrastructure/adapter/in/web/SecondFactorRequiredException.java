package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * A session was about to be built for an Account that needs its second factor and has not shown it
 * (ADR-0040). Reaching this means a sign-in path skipped {@link TotpGate}; failing closed turns
 * that mistake into an error instead of a way around the factor.
 */
public final class SecondFactorRequiredException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public SecondFactorRequiredException() {
    super("A session was requested for an account that has not completed its second factor");
  }
}
