package com.clavaris.identity.application.usecase.updateaccountprofile;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Input to {@link UpdateAccountProfileUseCase} — the first caller of {@code
 * Account.updateProfile(firstName, lastName)} (ADR-0026); an operator-driven edit today (dashboard
 * "View Profile" > Profile tab, {@code PlatformAccountProfileAdminController}), the same generic
 * shape a future self-service name-edit could reuse. {@code firstName}/{@code lastName}/{@code
 * username}/{@code phoneNumber} may each be {@code null} — the caller's own job to normalize a
 * blank submitted value, same convention every other optional text field in this codebase already
 * follows.
 *
 * @param username SDE-III review, 2026-09-22 — only ever applied when the Account has no username
 *     assigned yet: {@link com.clavaris.identity.domain.model.Account#assignUsername} is
 *     deliberately write-once (ADR-0024 §4, no change use case in v1), and this command does not
 *     relitigate that — {@link UpdateAccountProfileUseCase}'s own implementation silently ignores a
 *     submitted username when one is already assigned, rather than throwing, since the caller's own
 *     template never renders the field as editable in that case to begin with.
 * @param phoneNumber live feature request, 2026-09-22 — deliberately follows the exact same
 *     "applied only when currently absent" posture {@code username} does right above, at the
 *     caller's explicit request (not carried over from any pre-existing domain invariant the way
 *     username's is — {@code Account#updatePhoneNumber} itself is a plain overwrite, no uniqueness
 *     check, ADR-0024 §4 never applied to this field). {@link UpdateAccountProfileUseCase}'s own
 *     implementation silently ignores a submitted phone number once one is already set, same
 *     no-op-rather-than-throw posture as {@code username}.
 */
public record UpdateAccountProfileCommand(
    AccountId accountId,
    String firstName,
    String lastName,
    String username,
    String phoneNumber,
    AuditActor actor) {}
