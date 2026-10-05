package com.clavaris.identity.application.usecase.registeraccount;

/**
 * BR-ID-07: the submitted password matched a known-breached-password corpus entry. Deliberately a
 * generic message, never mentioning "breach"/the checking mechanism/a source — same
 * anti-enumeration posture {@code InvalidCredentialsException} already establishes for a different
 * kind of rejection, applied here per BR-ID-07's own explicit wording ("a generic 'choose a
 * different password' message, never revealing the source of the match"). Never carries the
 * password itself (BR-ID-01).
 */
public final class BreachedPasswordException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public BreachedPasswordException() {
    super("This password cannot be used — please choose a different one");
  }
}
