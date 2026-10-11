package com.clavaris.organization.application.usecase.getsocialconnections;

/** Where a provider stands for an Organization, as the Connections page shows it. */
public enum SocialConnectionStatus {
  /** Offered on the sign-in and sign-up pages. */
  ENABLED,
  /**
   * Switched on, but a PRODUCTION Organization with no credentials of its own: the provider is not
   * offered until they are saved.
   */
  SETUP_REQUIRED,
  /** Not offered. */
  DISABLED
}
