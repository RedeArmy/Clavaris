package com.clavaris.clientregistry.infrastructure.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/**
 * BR-PLATFORM-03: the blank-env-var path is a deliberate "log and skip", not a startup crash — this
 * is the exact branch a config regression (e.g. accidentally requiring these vars) would otherwise
 * silently change without a test asserting it. Plain unit test, no Spring context: the runner takes
 * its two env values as constructor parameters, so there's nothing framework-specific to exercise
 * here.
 */
class PlatformClientBootstrapRunnerTest {

  private final BootstrapPlatformClientUseCase useCase = mock(BootstrapPlatformClientUseCase.class);
  private final ApplicationArguments args = mock(ApplicationArguments.class);

  @Test
  void skipsBootstrappingWhenTheClientIdIsBlank() {
    new PlatformClientBootstrapRunner(useCase, "", "a-secret").run(args);

    verify(useCase, never()).handle(any());
  }

  @Test
  void skipsBootstrappingWhenTheClientSecretIsBlank() {
    new PlatformClientBootstrapRunner(useCase, "a-client-id", " ").run(args);

    verify(useCase, never()).handle(any());
  }

  @Test
  void bootstrapsWhenBothVariablesAreSet() {
    new PlatformClientBootstrapRunner(useCase, "a-client-id", "a-secret").run(args);

    verify(useCase).handle(any());
  }
}
