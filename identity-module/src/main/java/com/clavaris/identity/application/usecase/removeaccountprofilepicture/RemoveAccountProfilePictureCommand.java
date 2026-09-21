package com.clavaris.identity.application.usecase.removeaccountprofilepicture;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Input to {@link RemoveAccountProfilePictureUseCase}.
 *
 * @param actor SDE-III review, 2026-09-21 — same rationale {@code
 *     UpdateAccountProfilePictureCommand}'s own identical field documents: self-service vs.
 *     operator-driven, never hardcoded inside the service.
 */
public record RemoveAccountProfilePictureCommand(AccountId accountId, AuditActor actor) {

  /** Self-service shape — defaults {@code actor} to {@code AuditActor.account(accountId)}. */
  public RemoveAccountProfilePictureCommand(final AccountId accountId) {
    this(accountId, AuditActor.account(accountId.value()));
  }
}
