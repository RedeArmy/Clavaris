package com.clavaris.identity.application.usecase.activatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ActivateSigningKeyForOrganizationUseCase} — same shape as {@code
 * ActivatePlatformSigningKeyService}, scoped by {@link OrganizationId} instead of being a
 * process-wide singleton. At {@code CreateOrganization} time {@code findActive} is always empty (a
 * brand-new Organization has no prior key to retire); the retire step runs so this same service
 * doubles, unchanged, as ADR-0010 §5.2's manual rotation-with-overlap operation.
 *
 * <p>TD-SEC-002 (closed): re-activating the already-active {@code kid} (a double-submit of the same
 * provisioning call, or — symmetrically with the platform-tier fix — any future caller that
 * reconstructs {@code kid} from already-persisted state) is a no-op rather than a spurious
 * retire-then-reactivate of the very row that key material was just reloaded from.
 */
public class ActivateSigningKeyForOrganizationService
    implements ActivateSigningKeyForOrganizationUseCase {

  private final SigningKeyRepository repository;

  public ActivateSigningKeyForOrganizationService(final SigningKeyRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  @SuppressWarnings("PMD.OnlyOneReturn") // early-return no-op path reads clearer than nesting
  public SigningKey handle(
      final OrganizationId organizationId, final String kid, final String algorithm) {
    final Optional<SigningKey> currentlyActive = repository.findActive(organizationId);
    if (currentlyActive.isPresent() && currentlyActive.get().kid().equals(kid)) {
      return currentlyActive.get();
    }

    currentlyActive.ifPresent(
        previouslyActive -> {
          previouslyActive.retire();
          repository.save(previouslyActive);
        });

    final SigningKey activated = SigningKey.activate(organizationId, kid, algorithm);
    repository.save(activated);
    return activated;
  }
}
