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
 *
 * <p><b>SDE-III review, 2026-09-03 — real bug found and closed:</b> this read-retire-activate
 * sequence previously used a plain, unlocked read — two concurrent calls for the same Organization
 * (a double-submit of rotate, or a legitimate rotate racing an emergency purge) could each read the
 * same currently-active row, each retire it, and each independently activate a new one, leaving two
 * simultaneously-active keys for one Organization ({@code retired_at IS NULL} on both) — which key
 * new tokens sign under becomes nondeterministic, undermining the rotation-with-overlap invariant
 * CLAUDE.md §6 calls non-negotiable.
 *
 * <p>Closed via {@link SigningKeyRepository#lockForRotation}, a Postgres advisory lock keyed on
 * {@code organizationId}, acquired before the read below — not a row-level {@code SELECT ... FOR
 * UPDATE} on the "active" row, which was this fix's own first attempt and does not actually work:
 * live-verified (a real integration test, {@code SigningKeyRotationIntegrationTest}, caught this),
 * a {@code SELECT ... FOR UPDATE} row is re-qualified against its {@code WHERE} clause once the
 * blocking transaction commits, and retiring a key (setting {@code retired_at}) is exactly the
 * change that makes the row stop matching {@code retired_at IS NULL} — the second caller's lock
 * wait resolved to "no row found" rather than "here is the winner's new active row," so it still
 * went on to activate a second key of its own, tripping the unique index below and surfacing as an
 * uncaught 500. An advisory lock has no row to lose track of: keyed on {@code organizationId}
 * itself, it serializes every caller for the same Organization regardless of what {@code
 * signing_keys} looks like before or after, so the second caller's subsequent plain {@link
 * SigningKeyRepository#findActive} correctly sees the first caller's own newly-activated row. A
 * real {@code UNIQUE} partial index (migration {@code V20260903090000}) backs this as a DB-level
 * guarantee, not a replacement for it — same "constraint as belt-and-suspenders backstop" precedent
 * {@code V20260830110000}'s own deferred trigger already establishes for BR-ID-02.
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
    // See this class's own Javadoc — must happen before the read below, not after, or there is
    // nothing left for the lock to actually serialize.
    repository.lockForRotation(organizationId);
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
