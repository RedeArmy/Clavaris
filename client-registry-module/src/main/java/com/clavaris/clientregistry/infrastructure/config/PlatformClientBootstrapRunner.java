package com.clavaris.clientregistry.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientCommand;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.BootstrapPlatformClientUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * BR-PLATFORM-03: the ONLY path that ever seeds a {@code PlatformClient} — an {@code
 * ApplicationRunner}, not an HTTP endpoint, reading {@code PLATFORM_BOOTSTRAP_CLIENT_ID}/{@code
 * PLATFORM_BOOTSTRAP_CLIENT_SECRET} (.env.example) at every startup. {@link
 * BootstrapPlatformClientUseCase#handle} is itself idempotent, so a redeploy running this again is
 * always safe.
 */
@Component
class PlatformClientBootstrapRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformClientBootstrapRunner.class);

  private final BootstrapPlatformClientUseCase useCase;
  private final String clientId;
  private final String clientSecret;

  /* package */ PlatformClientBootstrapRunner(
      final BootstrapPlatformClientUseCase useCase,
      @Value("${PLATFORM_BOOTSTRAP_CLIENT_ID:}") final String clientId,
      @Value("${PLATFORM_BOOTSTRAP_CLIENT_SECRET:}") final String clientSecret) {
    this.useCase = useCase;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  @Override
  public void run(final ApplicationArguments args) {
    // Logged, not failed startup: an empty local/dev environment without these set yet (a fresh
    // clone before .env is filled in, per .env.example's own instructions) would otherwise
    // crash-loop the whole app over a management-API credential nothing but an operator action
    // needs yet. The cost of skipping is a clear, loud log line and a management API nobody can
    // reach until it's configured — never a silently-missing credential nobody notices at all.
    if (clientId.isBlank() || clientSecret.isBlank()) {
      LOG.warn(
          "PLATFORM_BOOTSTRAP_CLIENT_ID/PLATFORM_BOOTSTRAP_CLIENT_SECRET not set — the management "
              + "API (/api/v1/admin/**) will be unreachable until a PlatformClient is bootstrapped.");
      return;
    }
    useCase.handle(new BootstrapPlatformClientCommand(clientId, clientSecret));
  }
}
