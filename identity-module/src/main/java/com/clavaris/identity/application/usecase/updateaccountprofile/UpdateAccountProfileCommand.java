package com.clavaris.identity.application.usecase.updateaccountprofile;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Input to {@link UpdateAccountProfileUseCase} — the first caller of {@code
 * Account.updateProfile(firstName, lastName)} (ADR-0026); an operator-driven edit today (dashboard
 * "View Profile" > Profile tab, {@code PlatformAccountProfileAdminController}), the same generic
 * shape a future self-service name-edit could reuse. {@code firstName}/{@code lastName} may each be
 * {@code null} — the caller's own job to normalize a blank submitted value, same convention every
 * other optional text field in this codebase already follows.
 */
public record UpdateAccountProfileCommand(
    AccountId accountId, String firstName, String lastName, AuditActor actor) {}
