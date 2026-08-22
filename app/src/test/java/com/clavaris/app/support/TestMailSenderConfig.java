package com.clavaris.app.support;

import static org.mockito.Mockito.mock;

import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Overrides the real {@code ResendMailSender} with Mockito no-ops for every full
 * {@code @SpringBootTest} that exercises the real {@code /o/{organizationId}/register} (or
 * forgot-password/reset-password), {@code /platform/register}, or {@code /platform/forgot-password}
 * HTTP endpoints end to end — those flows now really do trigger email delivery (TD-SEC-004,
 * ADR-0012), and a test run must never make a real outbound call to Resend's API (no real {@code
 * RESEND_API_KEY} exists in CI, and even if one did, tests hitting a live third-party API is not
 * something this codebase does anywhere else). {@code ResendMailSender} implements both {@link
 * MailSender} and {@link PlatformMailSender} in one class, so both need their own override here —
 * mocking one leaves the other's bean candidate as the real adapter. {@code @Primary} on each so
 * these win without needing to exclude the real adapter from component scan.
 */
@TestConfiguration
public class TestMailSenderConfig {

  @Bean
  @Primary
  public MailSender mailSender() {
    return mock(MailSender.class);
  }

  @Bean
  @Primary
  public PlatformMailSender platformMailSender() {
    return mock(PlatformMailSender.class);
  }
}
