package com.clavaris.identity.application.usecase.activateplatformsigningkey;

import com.clavaris.identity.domain.model.PlatformSigningKey;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ActivatePlatformSigningKeyUseCase}. Because the actual key material
 * isn't persisted in this slice (see {@code PlatformSigningKeyMaterial}'s own Javadoc for why),
 * every process restart necessarily generates a fresh key pair — so the previously "active" row, if
 * any, is retired first: its own key material is gone the moment the old process exited, so leaving
 * it marked active would misrepresent what the running system can actually still verify against,
 * not just be stale bookkeeping.
 */
public class ActivatePlatformSigningKeyService implements ActivatePlatformSigningKeyUseCase {

  private final PlatformSigningKeyRepository repository;

  public ActivatePlatformSigningKeyService(final PlatformSigningKeyRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public PlatformSigningKey handle(final String kid, final String algorithm) {
    repository
        .findActive()
        .ifPresent(
            previouslyActive -> {
              previouslyActive.retire();
              repository.save(previouslyActive);
            });

    final PlatformSigningKey activated = PlatformSigningKey.activate(kid, algorithm);
    repository.save(activated);
    return activated;
  }
}
