package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

/**
 * ADR-0020 Decision 5: {@code TD-FUT-022} (Microsoft) is a real, named future value — rejecting an
 * unrecognized provider name here loudly, rather than silently persisting it as an inert string, is
 * what makes a typo (or a premature attempt to enable a provider that isn't built yet) a clean 400
 * instead of a policy that looks configured but does nothing.
 */
public final class UnknownSocialProviderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownSocialProviderException(final String provider) {
    super("Unknown social provider: " + provider);
  }
}
