package com.clavaris.identity.domain.model;

/**
 * Clerk "User permissions" parity (ADR-0026): two operator-controlled switches on an {@link
 * Account}, both off by default and never a self-toggle. Plain booleans, not nullable: there is no
 * "unset" state, every account has exactly one of two values for each from the moment it exists.
 *
 * @param canDeleteOwnAccount gates the self-service "Delete account" action
 * @param bypassesDeviceTrust lets a known-safe operator or service-style account skip the
 *     device-trust challenge while the Organization's {@code deviceTrustEnabled} stays on for
 *     everyone else
 */
// PMD.LongVariable: the component names are the Account accessors they back.
@SuppressWarnings("PMD.LongVariable")
public record AccountPermissions(boolean canDeleteOwnAccount, boolean bypassesDeviceTrust) {

  public static final AccountPermissions DEFAULT = new AccountPermissions(false, false);

  /* default */ AccountPermissions withSelfDelete(final boolean allowed) {
    return new AccountPermissions(allowed, bypassesDeviceTrust);
  }

  /* default */ AccountPermissions withDeviceTrustBypass(final boolean bypassed) {
    return new AccountPermissions(canDeleteOwnAccount, bypassed);
  }
}
