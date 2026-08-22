package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.Email;
import org.junit.jupiter.api.Test;

/** BR-ID-01: proves the raw password never appears in this command's string form. */
class AuthenticatePlatformAccountWithPasswordCommandTest {

  @Test
  void toStringNeverContainsTheRawPassword() {
    String rawPassword = "super-secret-value-must-not-leak";
    AuthenticatePlatformAccountWithPasswordCommand command =
        new AuthenticatePlatformAccountWithPasswordCommand(
            new Email("founder@example.com"), rawPassword);

    assertThat(command.toString()).doesNotContain(rawPassword).contains("REDACTED");
  }
}
