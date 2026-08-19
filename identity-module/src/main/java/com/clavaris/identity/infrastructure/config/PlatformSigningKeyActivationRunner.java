package com.clavaris.identity.infrastructure.config;

import com.clavaris.identity.application.usecase.activateplatformsigningkey.ActivatePlatformSigningKeyUseCase;
import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Records the {@link PlatformSigningKeyMaterial} bean's already-generated key as the active {@code
 * platform_signing_keys} row (retiring whichever row was active before, if any) — the key material
 * itself was generated the moment {@code PlatformSigningKeyMaterial} was constructed; this runner
 * only makes that event observable in the audit trail (CLAUDE.md §6).
 */
@Component
class PlatformSigningKeyActivationRunner implements ApplicationRunner {

  private static final String ALGORITHM = "RS256";

  private final ActivatePlatformSigningKeyUseCase useCase;
  private final PlatformSigningKeyMaterial material;

  /* package */ PlatformSigningKeyActivationRunner(
      final ActivatePlatformSigningKeyUseCase useCase, final PlatformSigningKeyMaterial material) {
    this.useCase = useCase;
    this.material = material;
  }

  @Override
  public void run(final ApplicationArguments args) {
    useCase.handle(material.kid(), ALGORITHM);
  }
}
