package com.clavaris.identity.application.usecase.activateplatformsigningkey;

import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ActivatePlatformSigningKeyUseCase}, called unconditionally on every
 * startup by {@code PlatformSigningKeyActivationRunner}.
 *
 * <p>TD-SEC-002 (closed): {@code PlatformSigningKeyMaterial} now reloads persisted key material on
 * restart instead of always generating a fresh key pair, so this method's first job is recognizing
 * that case — activating an already-active {@code kid} is a no-op, not a retire-then-reactivate of
 * the very row this same key material was just reloaded from. Only a genuinely different {@code
 * kid} (true first boot, or a deliberately forced rotation — {@code
 * incident-response-signing-key-compromise.md} §3/§6) retires the previous row: its key material is
 * gone or being deliberately superseded, so leaving it marked active would misrepresent what the
 * running system can actually still verify against.
 */
public class ActivatePlatformSigningKeyService implements ActivatePlatformSigningKeyUseCase {

  private final PlatformSigningKeyRepository repository;

  public ActivatePlatformSigningKeyService(final PlatformSigningKeyRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  @SuppressWarnings("PMD.OnlyOneReturn") // early-return no-op path reads clearer than nesting
  public PlatformSigningKey handle(final String kid, final String algorithm) {
    final Optional<PlatformSigningKey> currentlyActive = repository.findActive();
    if (currentlyActive.isPresent() && currentlyActive.get().kid().equals(kid)) {
      return currentlyActive.get();
    }

    currentlyActive.ifPresent(
        previouslyActive -> {
          previouslyActive.retire();
          repository.save(previouslyActive);
        });

    final PlatformSigningKey activated = PlatformSigningKey.activate(kid, algorithm);
    repository.save(activated);
    return activated;
  }
}
