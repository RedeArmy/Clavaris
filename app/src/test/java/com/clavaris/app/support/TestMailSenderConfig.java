package com.clavaris.app.support;

import static org.mockito.Mockito.mock;

import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Overrides the real {@code ResendMailSender} with a Mockito no-op for every full
 * {@code @SpringBootTest} that exercises the real {@code /o/{organizationId}/register} (or
 * forgot-password/reset-password) HTTP endpoints end to end — those flows now really do trigger
 * email delivery (TD-SEC-004), and a test run must never make a real outbound call to Resend's API
 * (no real {@code RESEND_API_KEY} exists in CI, and even if one did, tests hitting a live
 * third-party API is not something this codebase does anywhere else). {@code @Primary} so this wins
 * without needing to exclude the real adapter from component scan.
 */
@TestConfiguration
public class TestMailSenderConfig {

  @Bean
  @Primary
  public MailSender mailSender() {
    return mock(MailSender.class);
  }
}
