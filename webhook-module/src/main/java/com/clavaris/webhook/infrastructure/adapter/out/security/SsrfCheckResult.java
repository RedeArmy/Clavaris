package com.clavaris.webhook.infrastructure.adapter.out.security;

import java.net.InetAddress;
import java.util.List;

/**
 * TD-SEC-053: the outcome of {@link WebhookUrlSsrfChecker#check} — {@code reason} is {@code null}
 * exactly when {@code safe} is {@code true}, a human-readable explanation otherwise (surfaced in
 * the exception message at registration time, and in the delivery failure reason at delivery time).
 * {@code addresses} are the ones that were checked, empty when unsafe or when a caller did not
 * resolve anything.
 */
public record SsrfCheckResult(boolean safe, String reason, List<InetAddress> addresses) {

  // A constant, not a static safe() factory method — a record's auto-generated accessor for the
  // "safe" component is itself already called safe(); a same-named static method collides with it
  // (javac rejects the accessor as returning the wrong type), which is exactly what this avoids.
  // Named SAFE_RESULT, not SAFE — SonarCloud flagged SAFE as too easily confused with the "safe"
  // component/accessor it sits right next to, differing only by case.
  public static final SsrfCheckResult SAFE_RESULT = new SsrfCheckResult(true, null);

  public SsrfCheckResult {
    addresses = List.copyOf(addresses);
  }

  public SsrfCheckResult(final boolean safe, final String reason) {
    this(safe, reason, List.of());
  }

  // TD-SEC-097: a safe result that also carries the addresses that were checked, so the connection
  // can be made to those and no others (PinnedAddresses).
  public static SsrfCheckResult safeFor(final List<InetAddress> checked) {
    return new SsrfCheckResult(true, null, checked);
  }

  public static SsrfCheckResult unsafe(final String reason) {
    return new SsrfCheckResult(false, reason);
  }
}
