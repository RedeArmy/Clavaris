package com.clavaris.identity.application.usecase.activatesigningkeyfororganization;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ActivateSigningKeyForOrganizationUseCase} — same retire-then-activate
 * shape as {@code ActivatePlatformSigningKeyService}, scoped by {@link OrganizationId} instead of
 * being a process-wide singleton. At {@code CreateOrganization} time {@code findActive} is always
 * empty (a brand-new Organization has no prior key to retire); the retire step still runs so this
 * same service doubles, unchanged, as ADR-0010 §5.2's manual rotation-with-overlap operation.
 */
public class ActivateSigningKeyForOrganizationService
    implements ActivateSigningKeyForOrganizationUseCase {

  private final SigningKeyRepository repository;

  public ActivateSigningKeyForOrganizationService(final SigningKeyRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public SigningKey handle(
      final OrganizationId organizationId, final String kid, final String algorithm) {
    repository
        .findActive(organizationId)
        .ifPresent(
            previouslyActive -> {
              previouslyActive.retire();
              repository.save(previouslyActive);
            });

    final SigningKey activated = SigningKey.activate(organizationId, kid, algorithm);
    repository.save(activated);
    return activated;
  }
}
