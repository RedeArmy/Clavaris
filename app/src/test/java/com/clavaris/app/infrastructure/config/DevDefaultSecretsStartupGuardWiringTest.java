package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.app.ClavarisApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

/**
 * The unit tests prove the policy; this proves the guard is actually registered. A typo in {@code
 * META-INF/spring.factories}, or the file being dropped from the jar, would leave every unit test
 * green while production silently accepted the public development secrets.
 *
 * <p>Starts the real application with the {@code prod} profile and no secrets supplied. It must
 * fail while the environment is being prepared — before any bean, the database or Redis exist — so
 * this needs no Testcontainers and finishes in a fraction of a second. (The surrounding suite runs
 * under the {@code test} profile, which is why this one overrides it on the command line.)
 */
class DevDefaultSecretsStartupGuardWiringTest {

  @Test
  void theRealApplicationRefusesToStartWithTheProdProfileAndThePublicDevSecrets() {
    assertThatThrownBy(
            () ->
                SpringApplication.run(
                    ClavarisApplication.class,
                    "--spring.profiles.active=prod",
                    "--spring.main.web-application-type=none"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Refusing to start")
        .hasMessageContaining("clavaris.webhook.secret-encryption-key")
        .hasMessageContaining("WEBHOOK_SECRET_ENCRYPTION_KEY");
  }
}
