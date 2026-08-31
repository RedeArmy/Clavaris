package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

/**
 * ADR-0020 Decision 1: thrown when {@link
 * AuthenticateWithSocialProviderCommand#emailVerifiedByProvider} is {@code false} — this codebase's
 * whole linking design (BR-ID-09) depends on the provider's email claim being trustworthy; an
 * unverified email must never reach the account-lookup/link decision at all.
 */
public final class UnverifiedProviderEmailException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnverifiedProviderEmailException() {
    super("Social provider did not report a verified email");
  }
}
