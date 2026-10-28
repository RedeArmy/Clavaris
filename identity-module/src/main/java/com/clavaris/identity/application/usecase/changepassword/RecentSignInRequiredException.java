package com.clavaris.identity.application.usecase.changepassword;

/**
 * An Account with no password has nothing to prove who is asking, so a sensitive change needs a
 * sign-in made a moment ago instead (TD-FUT-053). Shown as "sign in again", never as a failure.
 */
public class RecentSignInRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RecentSignInRequiredException() {
    super("A recent sign-in is required to make this change");
  }
}
