package com.clavaris.webhook.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.UnsafeWebhookUrlException;
import org.junit.jupiter.api.Test;

class DnsResolvingWebhookUrlSsrfGuardTest {

  private final WebhookUrlSsrfChecker checker = mock(WebhookUrlSsrfChecker.class);
  private final DnsResolvingWebhookUrlSsrfGuard guard =
      new DnsResolvingWebhookUrlSsrfGuard(checker);

  @Test
  void doesNothingWhenTheCheckerReportsTheUrlIsSafe() {
    when(checker.check("https://example.com/hooks")).thenReturn(SsrfCheckResult.SAFE_RESULT);

    assertThatCode(() -> guard.requireSafeToRegister("https://example.com/hooks"))
        .doesNotThrowAnyException();
  }

  @Test
  void throwsUnsafeWebhookUrlExceptionCarryingTheCheckersOwnReasonWhenUnsafe() {
    when(checker.check("https://internal.example.com/hooks"))
        .thenReturn(SsrfCheckResult.unsafe("host resolves to 10.0.0.5 (private)"));

    assertThatExceptionOfType(UnsafeWebhookUrlException.class)
        .isThrownBy(() -> guard.requireSafeToRegister("https://internal.example.com/hooks"))
        .withMessageContaining("host resolves to 10.0.0.5 (private)");
  }
}
