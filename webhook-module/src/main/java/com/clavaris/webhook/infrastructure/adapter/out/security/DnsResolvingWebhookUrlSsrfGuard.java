package com.clavaris.webhook.infrastructure.adapter.out.security;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookUrlSsrfGuard;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-053: implements {@link WebhookUrlSsrfGuard} — a thin translation of {@link
 * WebhookUrlSsrfChecker}'s result into the application-layer exception {@code
 * RegisterWebhookEndpointService} expects, keeping {@code java.net.InetAddress} concepts entirely
 * out of the application layer.
 */
@Component
class DnsResolvingWebhookUrlSsrfGuard implements WebhookUrlSsrfGuard {

  private final WebhookUrlSsrfChecker checker;

  /* package */ DnsResolvingWebhookUrlSsrfGuard(final WebhookUrlSsrfChecker checker) {
    this.checker = checker;
  }

  @Override
  public void requireSafeToRegister(final String url) {
    final SsrfCheckResult result = checker.check(url);
    if (!result.safe()) {
      throw new UnsafeWebhookUrlException(result.reason());
    }
  }
}
