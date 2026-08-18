package com.clavaris.identity.application.usecase.registeraccount;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BR-ID-01 / CLAUDE.md §6: proves the raw password never appears in this command's string form — a
 * record's default {@code toString()} would otherwise print every component, {@code rawPassword}
 * included, making it a silent log-leak vector for anything that ever logs a command whole.
 */
class RegisterAccountCommandTest {

  @Test
  void toStringNeverContainsTheRawPassword() {
    String rawPassword = "super-secret-value-must-not-leak";
    RegisterAccountCommand command =
        new RegisterAccountCommand(
            new OrganizationId(UUID.randomUUID()), new Email("someone@example.com"), rawPassword);

    assertThat(command.toString()).doesNotContain(rawPassword).contains("REDACTED");
  }
}
