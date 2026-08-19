package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md §6: no PII/secrets in logs, ever — asserts the redaction actually happens, not just
 * that it's declared.
 */
class BootstrapPlatformClientCommandTest {

  @Test
  void toStringRedactsTheRawSecretButKeepsTheClientId() {
    BootstrapPlatformClientCommand command =
        new BootstrapPlatformClientCommand("bootstrap-client", "a-very-real-secret");

    assertThat(command.toString())
        .contains("bootstrap-client")
        .contains("REDACTED")
        .doesNotContain("a-very-real-secret");
  }
}
