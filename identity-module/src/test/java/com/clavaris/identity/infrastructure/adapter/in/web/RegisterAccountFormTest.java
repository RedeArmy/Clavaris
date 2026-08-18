package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** BR-ID-01 / CLAUDE.md §6: same rationale as {@code RegisterAccountCommandTest}. */
class RegisterAccountFormTest {

  @Test
  void toStringNeverContainsThePasswordOrItsConfirmation() {
    String rawPassword = "super-secret-value-must-not-leak";
    RegisterAccountForm form = new RegisterAccountForm();
    form.setEmail("someone@example.com");
    form.setPassword(rawPassword);
    form.setConfirmPassword(rawPassword);

    assertThat(form.toString()).doesNotContain(rawPassword).contains("REDACTED");
  }
}
