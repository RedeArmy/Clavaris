package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

/**
 * Code review finding: {@code Organization.withSocialLoginPolicy}'s own Javadoc already named
 * {@code enabled=true, providers=[]} as "a real no-op configuration state... left for the use case
 * layer to flag as a likely operator mistake" — this is that flag, actually implemented. Without
 * it, an operator could persist a policy that looks configured (a 200 OK, {@code enabled: true})
 * but silently never lets anyone actually sign in with a social provider, with no error anywhere in
 * the system to reveal the mistake.
 */
public final class SocialLoginEnabledWithNoProvidersException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SocialLoginEnabledWithNoProvidersException() {
    super("enabled=true requires at least one provider in providers");
  }
}
