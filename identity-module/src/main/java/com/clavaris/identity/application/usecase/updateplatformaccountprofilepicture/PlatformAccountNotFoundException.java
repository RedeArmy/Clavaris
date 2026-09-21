package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

import com.clavaris.identity.domain.model.PlatformAccountId;

/** Same rationale as {@code suspendplatformaccount.PlatformAccountNotFoundException}. */
public final class PlatformAccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformAccountNotFoundException(final PlatformAccountId platformAccountId) {
    super("No PlatformAccount exists with id " + platformAccountId);
  }
}
