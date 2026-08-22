package com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * BR-ID-01: proves neither the raw token nor the new raw password leaks via this command's string
 * form.
 */
class ConfirmPlatformAccountPasswordResetCommandTest {

  @Test
  void toStringNeverContainsTheRawTokenOrTheNewRawPassword() {
    String rawToken = "raw-token-value-must-not-leak";
    String newRawPassword = "new-password-value-must-not-leak";
    ConfirmPlatformAccountPasswordResetCommand command =
        new ConfirmPlatformAccountPasswordResetCommand(rawToken, newRawPassword);

    assertThat(command.toString())
        .doesNotContain(rawToken)
        .doesNotContain(newRawPassword)
        .contains("REDACTED");
  }
}
