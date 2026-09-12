package com.clavaris.identity.application.usecase.listsigningkeysfororganization;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only — same "an empty list is a valid, safe answer either way" posture {@code
 * ListOAuthClientsService} already establishes; in practice never actually empty (BR-ORG-06
 * guarantees a real Organization always has an active key), but this use case has no business
 * assuming that invariant holds instead of just querying for it.
 *
 * <p>{@code overlapWindow} is injected at construction (wired from the same {@code
 * clavaris.signing-key.jwks-overlap-hours} property {@code app}'s own {@code
 * OrganizationAuthorizationServerConfig} already reads for the real JWKS-serving path) rather than
 * hardcoded here — this use case's own "what should still be visible" cutoff must track that
 * property exactly, or the dashboard would show a key as gone that JWKS is still actually
 * publishing, or vice versa.
 */
public class ListSigningKeysForOrganizationService
    implements ListSigningKeysForOrganizationUseCase {

  private final SigningKeyRepository signingKeys;
  private final Duration overlapWindow;

  public ListSigningKeysForOrganizationService(
      final SigningKeyRepository signingKeys, final Duration overlapWindow) {
    this.signingKeys = signingKeys;
    this.overlapWindow = overlapWindow;
  }

  @Override
  public List<SigningKey> handle(final OrganizationId organizationId) {
    return signingKeys.findActiveAndRetiredSince(
        organizationId, Instant.now().minus(overlapWindow));
  }
}
