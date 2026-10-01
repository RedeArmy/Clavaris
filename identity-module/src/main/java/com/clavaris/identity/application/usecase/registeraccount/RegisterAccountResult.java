package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * TD-FUT-019: widened from a bare {@code AccountId} so {@link RegisterAccountController} can tell a
 * gated signup apart from an ordinary one without a second lookup — {@code pendingApproval} is
 * {@code true} only when the owning Organization's own {@code
 * AccountAuthenticationPolicy.selfRegistrationRequiresApproval()} was on at the moment this
 * specific account was created, mirroring the {@code Account} row's own {@code
 * AccountStatus.PENDING_APPROVAL} it was persisted with.
 */
public record RegisterAccountResult(AccountId accountId, boolean pendingApproval) {}
