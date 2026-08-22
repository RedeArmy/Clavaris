package com.clavaris.identity.application.usecase.registerplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.Email;
import org.junit.jupiter.api.Test;

/**
 * BR-ID-01: proves the raw password never appears in this command's string form — same rationale as
 * {@code registeraccount.RegisterAccountCommandTest}.
 */
class RegisterPlatformAccountCommandTest {

  @Test
  void toStringNeverContainsTheRawPassword() {
    String rawPassword = "super-secret-value-must-not-leak";
    RegisterPlatformAccountCommand command =
        new RegisterPlatformAccountCommand(new Email("founder@example.com"), rawPassword);

    assertThat(command.toString()).doesNotContain(rawPassword).contains("REDACTED");
  }
}
