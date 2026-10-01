package com.clavaris.identity.application.usecase.deletewebauthncredential;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;

/**
 * @param accountId the resolved owner to scope the delete against — the current self-service
 *     session's own Account, or an admin-resolved target Account; never trusted from the client
 *     beyond that resolution.
 * @param actor TD-SEC-034: same rationale {@code RevokeAccountSessionCommand}'s own identical field
 *     documents — removing a passkey is a security-relevant mutation, audited either way (self or
 *     operator).
 */
public record DeleteWebAuthnCredentialCommand(
    UUID credentialId, AccountId accountId, AuditActor actor) {

  /** Self-service shape — defaults {@code actor} to {@code AuditActor.account(accountId)}. */
  public DeleteWebAuthnCredentialCommand(final UUID credentialId, final AccountId accountId) {
    this(credentialId, accountId, AuditActor.account(accountId.value()));
  }
}
