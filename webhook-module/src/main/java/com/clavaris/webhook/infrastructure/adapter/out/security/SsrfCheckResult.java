package com.clavaris.webhook.infrastructure.adapter.out.security;

/**
 * TD-SEC-053: the outcome of {@link WebhookUrlSsrfChecker#check} — {@code reason} is {@code null}
 * exactly when {@code safe} is {@code true}, a human-readable explanation otherwise (surfaced in
 * the exception message at registration time, and in the delivery failure reason at delivery time).
 */
public record SsrfCheckResult(boolean safe, String reason) {

  // A constant, not a static safe() factory method — a record's auto-generated accessor for the
  // "safe" component is itself already called safe(); a same-named static method collides with it
  // (javac rejects the accessor as returning the wrong type), which is exactly what this avoids.
  public static final SsrfCheckResult SAFE = new SsrfCheckResult(true, null);

  public static SsrfCheckResult unsafe(final String reason) {
    return new SsrfCheckResult(false, reason);
  }
}
