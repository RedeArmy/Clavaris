package com.clavaris.clientregistry.application.usecase.requestclientdomainconfig;

/**
 * ADR-0009 §2 / STRIDE (custom-domain takeover): a hostname is unique across every {@code
 * OAuthClient} in the whole system, not just within one Organization — the DNS TXT-record challenge
 * only proves ownership of the domain at the DNS level, never which tenant is entitled to claim it,
 * so a second client requesting an already-claimed hostname (even under a different Organization)
 * must be rejected outright rather than silently overwriting the first claim.
 */
public final class HostnameAlreadyClaimedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public HostnameAlreadyClaimedException(final String hostname) {
    super("hostname is already claimed by another OAuthClient: " + hostname);
  }
}
