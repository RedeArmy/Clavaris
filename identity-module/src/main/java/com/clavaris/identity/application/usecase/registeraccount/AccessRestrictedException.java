package com.clavaris.identity.application.usecase.registeraccount;

/**
 * Thrown when {@link AccessRestrictionPolicyProvider#isAllowed} rejects the email — the
 * Organization's own blocklist/allowlist, not a system-wide rule. Never exposes which list or which
 * entry matched, same anti-enumeration posture {@link EmailAlreadyRegisteredException}'s own
 * controller-side handling already applies.
 */
public final class AccessRestrictedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccessRestrictedException() {
    super("This email is not allowed to register for this Organization");
  }
}
