package com.clavaris.webhook.application.usecase.registerwebhookendpoint;

/**
 * TD-SEC-053: outbound port — the real SSRF guard for an operator-supplied webhook URL, checking
 * where the host actually resolves to, not just its scheme (that part is already {@code
 * WebhookEndpoint}'s own {@code requireValidUrl}, BR-WEBHOOK-07). Deliberately kept out of {@code
 * domain/model} — DNS resolution is I/O, which the hexagonal dependency rule forbids there.
 * Implemented by {@code infrastructure/adapter/out/security/DnsResolvingWebhookUrlSsrfGuard}, a
 * thin wrapper around {@code WebhookUrlSsrfChecker} (the same checker {@code JdkHttpWebhookSender}
 * calls directly at delivery time — see that class's own Javadoc for why both call sites exist).
 */
@FunctionalInterface
public interface WebhookUrlSsrfGuard {

  /**
   * @throws UnsafeWebhookUrlException if {@code url}'s host resolves to any
   *     private/loopback/link-local/multicast/unspecified address, or does not resolve at all.
   */
  void requireSafeToRegister(String url);
}
